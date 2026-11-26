"""核验本机独立 HTTP 托管的导出文件和原生表单依赖，按需将结果交给工作台。"""
import argparse
from datetime import datetime, timezone
import hashlib
from importlib import import_module
import json
import os
import re
import tempfile
from html.parser import HTMLParser
from pathlib import Path, PurePosixPath
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Forms(HTMLParser):
    def __init__(self):
        super().__init__()
        self.categories = set()

    def handle_starttag(self, tag, attrs):
        category = dict(attrs).get("data-cms-form-category")
        if tag == "form" and category and re.fullmatch(r"[1-9][0-9]*", category):
            self.categories.add(int(category))


def local_path(root, relative):
    path = PurePosixPath(relative)
    if not path.parts or path.is_absolute() or ".." in path.parts or any(c in relative for c in "\\:%?#"):
        raise ValueError(f"交付路径无效 {relative}")
    actual = (root / path).resolve()
    if not actual.is_relative_to(root):
        raise ValueError(f"路径超出目录 {relative}")
    return actual


def digest(stream):
    return hashlib.file_digest(stream, "sha256").hexdigest()


def write_json(target, data):
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False, mode="w", encoding="utf8") as f:
        name = f.name
        json.dump(data, f, ensure_ascii=False, indent=2, sort_keys=True)
    try:
        os.replace(name, target)
    finally:
        Path(name).unlink(missing_ok=True)



class SearchForms(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.search = []
        self.feed(html)
        self.close()

    def handle_starttag(self, tag, attrs):
        fields = dict(attrs)
        action = fields.get("action") or ""
        if tag == "form" and "search" in action:
            self.search.append((action, (fields.get("method") or "get").lower(),
                                "data-cms-header-search" in fields))


def offline_search(root, source, manifest):
    from prepare_static_search import DESTINATION, prepare_static_search

    pages, files = manifest["pages"], manifest["files"]
    original = {relative: local_path(source, relative).read_text(encoding="utf-8-sig") for relative in pages}
    prepared = dict(original)
    payload = prepare_static_search(manifest["siteCode"], manifest["packageVersion"], prepared)
    if not payload:
        return None
    destination, expected = payload
    if destination != DESTINATION or destination not in files:
        raise ValueError("交付文件缺少已审离线搜索结果页")
    field = re.compile(r'(<script type="application/json" id="search-documents">)(.*?)(</script>)', re.S)

    def index(html):
        matches = list(field.finditer(html))
        if len(matches) != 1:
            raise ValueError("搜索页缺少唯一的站内索引")
        documents = json.loads(matches[0].group(2))
        if (not isinstance(documents, list) or not documents or
                any(not isinstance(doc, dict) or set(doc) != {"path", "title", "text"}
                    or not all(isinstance(value, str) for value in doc.values()) for doc in documents)):
            raise ValueError("离线索引格式无效")
        by_path = {doc["path"]: doc for doc in documents}
        if len(by_path) != len(documents):
            raise ValueError("离线索引包含重复页面")
        return field.sub(r"\1__SEARCH_DOCUMENTS__\3", html), by_path

    observed = local_path(root, destination).read_text(encoding="utf-8-sig")
    if index(observed) != index(expected):
        raise ValueError("离线搜索入口脚本或索引与本次正式源页不符")
    covered = {}
    for relative, html in prepared.items():
        if html == original[relative]:
            continue
        before = SearchForms(original[relative]).search
        after = SearchForms(local_path(root, relative).read_text(encoding="utf-8-sig")).search
        if before == [("/__cms/search", "get", True)] and after == [("/" + destination, "get", True)]:
            covered[relative] = pages[relative]
    if not covered or "index.html" not in covered:
        raise ValueError("离线结果页未绑定已审首页搜索入口")
    return {"resultDigest": files[destination], "sources": covered}

def verify(args):
    origin = args.origin.rstrip("/")
    url = urlsplit(origin)
    if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password or url.path or url.query or url.fragment:
        raise ValueError("请指定独立 HTTP(S) 站点源地址")
    root = args.directory.resolve()
    manifest = json.loads((root / "_cms-static-export.json").read_text(encoding="utf-8-sig"))
    code, version = manifest["siteCode"], manifest["packageVersion"]
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}", code):
        raise ValueError("无效站点编码")
    if args.site_id < 1:
        raise ValueError("无效站点编号")
    exporter = import_module("export-static")
    static_root, packages, _ = exporter.settings_paths(args.runtime.resolve())
    source = (static_root / code).resolve()
    files, pages = manifest["files"], manifest["pages"]
    if not files or "index.html" not in pages or not set(pages).issubset(files):
        raise ValueError("清单缺少首页或页面产物")
    http = build_opener(ProxyHandler({}), NoRedirect())
    def get(path, timeout=15):
        if not args.connect_loopback:
            return http.open(origin + path, timeout=timeout)
        if url.scheme != "http" or not url.hostname.endswith((".localhost", ".test")):
            raise ValueError("回环映射仅适用于本机 HTTP 测试域名")
        local = f"http://127.0.0.1:{url.port or 80}{path}"
        return http.open(Request(local, headers={"Host":url.netloc}), timeout=timeout)

    issues = [x for x in manifest["issues"] if x["kind"] != "service-dependency"]
    def page_issue(kind, page, url, line, reason):
        issues.append({"kind":kind, "page":page, "reason":reason})

    receipt_root = packages / ".workbench-site-model" / code
    current_pages = {}
    try:
        if not exporter.ordinary_directory(static_root / code):
            raise ValueError("CMS 当前静态产物目录不可用")
        for name, checksum, _ in exporter.generated_pages(source, receipt_root, page_issue):
            current_pages[name] = checksum
    except (OSError, ValueError) as exc:
        issues.append({"kind":"source-inventory", "page":"", "reason":str(exc)})
    for name in sorted(current_pages.keys() - pages.keys()):
        issues.append({"kind":"missing-page", "page":name, "reason":"独立交付清单缺少当前 CMS 正式页"})
    for name in sorted(pages.keys() - current_pages.keys()):
        issues.append({"kind":"obsolete-page", "page":name, "reason":"CMS 当前正式页面集合已移除此页"})
    form_categories = set()
    for rel, expected in sorted(files.items()):
        try:
            file = local_path(root, rel)
            path = "/" + quote(rel, safe="/")
            with file.open("rb") as stream:
                if digest(stream) != expected:
                    raise ValueError("交付文件与清单不一致")
            with get(path) as response:
                if response.status != 200 or digest(response) != expected:
                    raise ValueError("HTTP 响应与交付文件不一致")
            if rel in pages:
                if rel in current_pages and current_pages[rel] != pages[rel]:
                    raise ValueError("CMS 产物已重新生成，请重新导出")
                form = Forms()
                form.feed(file.read_text(encoding="utf-8-sig"))
                form_categories.update(form.categories)
        except (ValueError, OSError, HTTPError, URLError) as exc:
            issues.append({"kind":"artifact","page":rel,"reason":str(exc)})
    try:
        with get("/") as response:
            if digest(response) != files["index.html"]:
                raise ValueError("根地址和导出首页不同")
    except (ValueError, OSError) as exc:
        issues.append({"kind":"homepage","page":"/","reason":str(exc)})
    verified = []
    for category in sorted(form_categories):
        try:
            with get("/api/form/public/script.js", timeout=8) as r:
                if b"data-cms-form-category" not in r.read(250000):
                    raise ValueError("表单脚本未返回")
            for endpoint in ("definition", "captcha"):
                with get(f"/api/form/public/{endpoint}?categoryId={category}", timeout=8) as r:
                    obj=json.load(r)
                data=obj.get("data") or {}
                valid = data.get("fields") if endpoint=="definition" else data.get("imageBase64") and data.get("captchaKey")
                if obj.get("code")!=0 or not valid:
                    raise ValueError(f"{endpoint} 的公开响应无效")
            verified.append(category)
        except (ValueError, OSError, AttributeError) as exc:
            issues.append({"kind":"form-service","categoryId":category,"reason":str(exc)})
    for issue in manifest["issues"]:
        if issue["kind"]=="service-dependency" and not (form_categories and urlsplit(issue.get("url","")).path in {
                "/api/form/public/script.js","/api/form/public/captcha","/api/form/public/definition","/api/form/public/submit"}):
            issues.append(issue)
    search = None
    if not issues:
        try:
            search = offline_search(root, source, manifest)
        except (ValueError, OSError, TypeError) as exc:
            issues.append({"kind": "offline-search", "page": "other/search.html", "reason": str(exc)})
    report = {"siteId":args.site_id,"siteCode":code,"packageVersion":version,"origin":origin,
              "directory":str(root),"verifiedAt":datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
              "manifestDigest":hashlib.sha256((root/"_cms-static-export.json").read_bytes()).hexdigest(),
              "sourcePages":pages,"verifiedForms":verified,"filesChecked":len(files),"issueCount":len(issues),
              "firstIssue":str(issues[0].get("page",""))+" "+str(issues[0].get("reason","")) if issues else ""}
    if args.report:
        write_json(args.report, {**report,"issues":issues})
    if args.record:
        workspace=(packages/".workbench-site-model").resolve()
        target=local_path(workspace,f"{code}/{args.site_id}/static-host-check.json")
        if not target.parent.is_dir():
            raise ValueError("请先打开该站点工作台并核对站点 ID")
        write_json(target,report)
        search_target=local_path(workspace,f"{code}/{args.site_id}/static-search-check.json")
        proof = {}
        if search:
            with target.open("rb") as stream:
                proof = {**search, "hostDigest": digest(stream), "manifestDigest": report["manifestDigest"]}
        write_json(search_target,proof)
    print(f"{code} · 已校验 {len(files)} 个文件，表单分类 {verified}，待处理 {len(issues)} 项")
    return 1 if issues else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory",type=Path,required=True)
    parser.add_argument("--origin",required=True)
    parser.add_argument("--site-id",type=int,required=True)
    parser.add_argument("--runtime",type=Path,required=True)
    parser.add_argument("--report",type=Path)
    parser.add_argument("--connect-loopback",action="store_true",help="将本机测试域名的连接定向到回环地址，保留 Host")
    parser.add_argument("--record",action="store_true",help="将本次独立托管核验结果写入该站点本机工作区")
    raise SystemExit(verify(parser.parse_args()))
