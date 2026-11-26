"""从 CMS 静态生成目录和指定原包构建可独立部署的站点。"""

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import tempfile
from html import unescape
from html.parser import HTMLParser
from http.client import HTTPConnection, HTTPException, HTTPSConnection
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path, PurePosixPath
from urllib.parse import quote, unquote, urljoin, urlsplit
from zipfile import ZIP_DEFLATED, ZipFile

ASSETS = frozenset("""
    .css .js .mjs .jpg .jpeg .png .gif .webp .avif .svg .bmp .ico
    .woff .woff2 .ttf .otf .eot .mp4 .webm .ogg .mp3 .wav .m4a .pdf
""".split())
TEXT_ASSETS = frozenset({".css", ".js", ".mjs"})
DELIVERY_ASSETS = ASSETS - {".mp4", ".webm", ".ogg", ".mp3", ".wav", ".m4a", ".pdf"} | {".txt"}
FRAGMENTS = {".html", ".htm", ".asp", ".aspx"}
SAFE_CODE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
VERSION = re.compile(r"v[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
RESERVED = re.compile(
    r"^(?:api|__cms|admin|app_data|app_code|bin|logs?|secrets?|private|"
    r"node_modules|web-inf|meta-inf|credentials?|env)(?:$|[._-])",
    re.IGNORECASE,
)
DEVICE = re.compile(r"^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)", re.IGNORECASE)
SERVER_MARKUP = re.compile(
    r"<%|<\?(?:php|=)|<!--\s*#|<\s*/?\s*asp:|\brunat\s*=\s*(['\"]?)server\b\1|"
    r"\b(?:th|data-th)[:\-](?:include|replace|insert)\s*=",
    re.IGNORECASE,
)
FULL_PAGE = re.compile(r"<\s*(?:!doctype|html|head|body)\b", re.IGNORECASE)
STYLE_URL = re.compile(
    r"""url\(\s*(?:"([^"]*)"|'([^']*)'|([^)'"]*?))\s*\)|@import\s+["']([^"']+)["']""",
    re.IGNORECASE,
)
SCRIPT_URL = re.compile(
    r"""(?:\b(?:fetch|load|import)\s*\(\s*|\b(?:url|from)\s*[: ]\s*)["']([^"'\s]+)["']""",
    re.IGNORECASE,
)
PUBLIC_SERVICE = re.compile(r"""(?<![\w/])/(?:api|__cms)/[\w./-]+""")
DELIVERY_ORIGIN = "https://delivery.invalid"
HOME_ALIAS = re.compile(r"/index(?:\.(?:html|htm|aspx|asp))?", re.IGNORECASE)
VENDOR_PATH = "assets/vendor/"
OTHER_ASSET_ROOTS = ("assets/global/", "assets/upload/", VENDOR_PATH)
EXTERNAL_URL = re.compile(r"""(?<![\w:/])(?:https?:)?//[a-z0-9][^"'<>\s()]+""", re.IGNORECASE)
SHA256 = re.compile(r"[0-9a-f]{64}")
ICON_TYPES = {".ico", ".png", ".svg", ".gif", ".webp", ".jpg", ".jpeg", ".avif"}
ICON_RELATIONS = {"icon", "apple-touch-icon", "apple-touch-icon-precomposed", "mask-icon"}
OPTIONAL_ICON_RELATIONS = {frozenset({rel}) for rel in ICON_RELATIONS} | {
    frozenset({"shortcut", "icon"}), frozenset({"bookmark"}),
}
ICON_OMISSION_CONFIG = Path(__file__).with_name("static-dependencies") / "icon-omissions.json"
METADATA_TAGS = {"html", "head", "body", "base", "link", "meta", "script", "style"}
HTML_ATTRIBUTE = re.compile(
    r"""([^\s=<>/]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>]+)))?"""
)


def safe_name(part):
    return (
        part and not part.startswith(".") and not part.endswith((" ", "."))
        and not any(char in part for char in '/\\:%<>|?*"')
        and not any(ord(char) < 32 for char in part)
        and not RESERVED.match(part) and not DEVICE.match(part)
    )


def ordinary(path, directory=False):
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        return False
    test = stat.S_ISDIR if directory else stat.S_ISREG
    return test(metadata.st_mode) and not (
        getattr(metadata, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT
    )


def ordinary_directory(path):
    return all(ordinary(parent, directory=True) for parent in (path, *path.parents))


def source_file(root, parts):
    if not all(safe_name(part) for part in parts):
        return None
    path = root
    for part in parts[:-1]:
        path /= part
        if not ordinary(path, directory=True):
            return None
    target = path / parts[-1]
    return target if ordinary(target) else None


def walk_files(root):
    for directory, children, files in os.walk(root, followlinks=False):
        folder = Path(directory)
        children[:] = sorted(name for name in children
                             if safe_name(name) and ordinary(folder / name, directory=True))
        for name in sorted(files):
            path = folder / name
            if safe_name(name) and ordinary(path):
                yield path.relative_to(root).as_posix(), path


def generated_pages(generated, receipt_root, issue):
    # ASP 扩展名也可能是正常生成的 HTML；须由本机 CMS 的文件路径及内容摘要同时证明。
    proof_dirs = []
    if receipt_root is not None and ordinary_directory(receipt_root):
        proof_dirs = [entry / "static-artifacts" for entry in receipt_root.iterdir()
                      if entry.name.isdecimal() and ordinary_directory(entry / "static-artifacts")]
    for relative, source in walk_files(generated):
        if suffix_of(relative) not in FRAGMENTS:
            continue
        if relative.startswith(VENDOR_PATH):
            raise ValueError(f"正式页面占用了外域依赖路径：{relative}")
        raw = source.read_bytes()
        content_digest = hashlib.sha256(raw).hexdigest()
        if suffix_of(relative) in {".asp", ".aspx"}:
            proof_key = hashlib.sha256(str(source.resolve()).encode("utf-8")).hexdigest() + ".txt"
            if not any(ordinary(proof) and proof.stat().st_size == 129
                       and re.fullmatch(r"[0-9a-f]{64}:" + content_digest, proof.read_text(encoding="ascii", errors="replace"))
                       for proof in (directory / proof_key for directory in proof_dirs)):
                issue("missing-render-proof", relative, relative, 1, "无匹配的正式渲染回执，请从 CMS 重新生成此页")
                continue
        text = raw.decode("utf-8-sig")
        if SERVER_MARKUP.search(text):
            raise ValueError(f"已生成页面仍含服务器指令：{relative}")
        yield relative, content_digest, text


def settings_paths(runtime):
    try:
        import yaml
    except ImportError as exc:
        raise ValueError("请安装 PyYAML（python -m pip install PyYAML）") from exc
    path = runtime / "settings.yml"
    settings = yaml.safe_load(path.read_text(encoding="utf-8-sig")) if path.exists() else {}
    cms = (settings or {}).get("cms", {})

    def configured(section, key, default):
        value = cms.get(section, {}).get(key)
        if value is None:
            return runtime / default
        if not isinstance(value, str) or "${" in value:
            raise ValueError(f"离线导出需要 cms.{section}.{key} 的明确目录")
        candidate = Path(value)
        return candidate if candidate.is_absolute() else runtime / candidate

    return (
        configured("html", "save-root", "static-output"),
        configured("ui-package", "root", "ui-packages"),
        configured("static-asset", "root", "assets"),
    )


def css_references(text, first_line=1):
    for match in STYLE_URL.finditer(text):
        yield next(group for group in match.groups() if group is not None), (
            first_line + text.count("\n", 0, match.start())
        )


class HtmlReferences(HTMLParser):
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.references = []
        self.links = []
        self.has_base = False
        self.inline = None
        self.feed(text)
        self.close()

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        line = self.getpos()[0]
        if tag == "base" and "href" in attrs:
            self.has_base = True
        if tag in {"a", "area"} and attrs.get("href"):
            self.links.append((attrs["href"], self.getpos(), self.get_starttag_text()))
        for name, value in attributes:
            if not value:
                continue
            suffix = suffix_of(value)
            if name in {"src", "poster", "data-loadimg", "data-lazy", "data-original",
                        "data-bg", "data-background"}:
                self.references.append((value, line, False))
            elif name in {"srcset", "data-srcset"} and not value.lstrip().startswith("data:"):
                self.references.extend(
                    (part.strip().split()[0], line, False)
                    for part in value.split(",") if part.strip()
                )
            elif name == "data-cms-fragment-path" or name == "data-src" and suffix in FRAGMENTS:
                self.references.append((value, line, True))
            elif name in {"data-src", "data-cms-form-action"} or tag == "form" and name == "action":
                self.references.append((value, line, False))
            elif name in {"href", "xlink:href"} and (
                tag in {"use", "image"} or suffix in ASSETS
                or tag == "link" and set((attrs.get("rel") or "").lower().split())
                & {"stylesheet", "icon", "preload", "modulepreload", "manifest"}
            ):
                self.references.append((value, line, False))
            elif name == "style":
                self.references.extend((url, row, False) for url, row in css_references(value, line))
        if tag in {"style", "script"}:
            self.inline = tag

    def handle_startendtag(self, tag, attributes):
        self.handle_starttag(tag, attributes)
        self.handle_endtag(tag)

    def handle_endtag(self, tag):
        if self.inline == tag:
            self.inline = None

    def handle_data(self, text):
        line = self.getpos()[0]
        if self.inline == "style":
            self.references.extend((url, row, False) for url, row in css_references(text, line))
        elif self.inline == "script":
            self.references.extend(script_references(text, line))


class IconLinks(HTMLParser):
    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.links = []
        self.head = False
        self.prologue = True
        self.has_base = False
        self.inert = []
        self.feed(text)
        self.close()

    def handle_starttag(self, tag, attributes):
        if tag in {"title", "template", "noscript"}:
            self.inert.append(tag)
        if self.inert:
            return
        if tag == "head":
            self.head = True
        if tag == "body":
            self.head = False
        if tag not in METADATA_TAGS:
            self.prologue = False
        attrs = dict(attributes)
        if tag == "base" and "href" in attrs:
            self.has_base = True
        if not (self.head or self.prologue) or tag != "link" or len(attrs) != len(attributes):
            return
        href = attrs.get("href") or ""
        rel = frozenset((attrs.get("rel") or "").lower().split())
        suffix = suffix_of(href)
        if suffix in ICON_TYPES and (rel & ICON_RELATIONS or rel == {"bookmark"} and suffix == ".ico"):
            self.links.append((href, rel, self.getpos(), self.get_starttag_text()))

    def handle_endtag(self, tag):
        if self.inert:
            if tag == self.inert[-1]:
                self.inert.pop()
            return
        if tag == "head":
            self.head = False


def relative_icon_path(href):
    url = urlsplit(href)
    if url.scheme or url.netloc or not url.path or url.path.startswith("/") or "\\" in url.path:
        return None
    if re.search(r"%(?:2f|5c)", url.path, re.IGNORECASE):
        return None
    decoded = unquote(url.path)
    parts = decoded.split("/")
    while parts and parts[0] in {".", ".."}:
        parts.pop(0)
    return decoded if parts and all(map(safe_name, parts)) else None


def icon_path_from(document, relative):
    base = f"{DELIVERY_ORIGIN}/{quote(document)}"
    return unquote(urlsplit(urljoin(base, quote(relative))).path).lstrip("/")


def suffix_of(value):
    try:
        return PurePosixPath(urlsplit(value).path).suffix.lower()
    except ValueError:
        return ""


def script_references(text, first_line=1):
    for match in SCRIPT_URL.finditer(text):
        url = match.group(1)
        suffix = suffix_of(url)
        if (suffix in FRAGMENTS or suffix in ASSETS or PUBLIC_SERVICE.match(url)
                or EXTERNAL_URL.fullmatch(url)):
            yield url, first_line + text.count("\n", 0, match.start()), suffix in FRAGMENTS
    for match in PUBLIC_SERVICE.finditer(text):
        yield match.group(), first_line + text.count("\n", 0, match.start()), False


def read_fragment(source):
    with source.open("rb") as handle:
        raw = handle.read(2 * 1024 * 1024 + 1)
    if len(raw) > 2 * 1024 * 1024:
        raise ValueError("原包片段超过 2 MB")
    text = raw.decode("utf-8-sig")
    if SERVER_MARKUP.search(text) or FULL_PAGE.search(text):
        raise ValueError("原包目标含服务器指令或是完整页面")
    return text


def external_key(url):
    parts = urlsplit("https:" + url if url.startswith("//") else url)
    return parts._replace(fragment="").geturl()


class ExternalAssets:
    """只使用获准的本地缓存字节，导出期间不发网络请求。"""

    def __init__(self, lock):
        self.urls = {}
        self.paths = {}
        self.notices = {}
        if lock is None:
            return
        cache = lock.parent / "blobs"
        data = json.loads(lock.read_text(encoding="utf-8-sig"))
        for url, digest in data["resources"].items():
            parsed = urlsplit(url)
            parts = [unquote(part, errors="strict") for part in parsed.path.lstrip("/").split("/")]
            if (parsed.scheme != "https" or not parsed.hostname or parsed.netloc != parsed.hostname
                    or not re.fullmatch(r"[a-z0-9][a-z0-9.-]+", parsed.hostname)
                    or parsed.fragment or not all(part.isprintable() and safe_name(part) for part in parts)
                    or suffix_of(url) not in ASSETS or not SHA256.fullmatch(digest)):
                raise ValueError(f"外域依赖锁中的资源不合法：{url}")
            output = VENDOR_PATH + parsed.hostname + "/" + "/".join(parts)
            if output in self.paths:
                raise ValueError(f"外域依赖锁指向相同交付路径：{output}")
            source = source_file(cache, [digest]) if ordinary_directory(cache) else None
            if source is None or file_digest(source) != digest:
                continue
            self.urls[url] = output
            self.paths[output] = {"url": url, "sha256": digest, "source": source}
        for url, notice in data.get("notices", {}).items():
            if url not in self.urls:
                continue
            name, digest = notice["name"], notice["sha256"]
            if not safe_name(name) or suffix_of(name) not in {".txt", ".md"} or not SHA256.fullmatch(digest):
                raise ValueError(f"外域依赖锁中的版权声明不合法：{name}")
            source = source_file(cache, [digest]) if ordinary_directory(cache) else None
            if source is None or file_digest(source) != digest:
                raise ValueError(f"外域依赖缺少已锁定的版权声明：{name}")
            self.notices[self.urls[url]] = (VENDOR_PATH + "notices/" + name, source)

    def rewrite(self, text):
        def replace(match):
            value = match.group()
            try:
                key = external_key(unescape(value))
            except ValueError:
                return value
            output = self.urls.get(key)
            if not output:
                return value
            suffix_at = min((at for marker in ("?", "#") if (at := value.find(marker)) >= 0),
                            default=len(value))
            return "/" + quote(output) + value[suffix_at:]

        return EXTERNAL_URL.sub(replace, text) if self.urls else text


class Export:
    def __init__(self, code, version, package, global_assets, output, origins, external):
        self.code = code
        self.version = version
        self.home_origins = [urlsplit(value) for value in origins
                             if urlsplit(value).hostname in {"localhost", "127.0.0.1", "::1"}]
        self.package = package
        delivery = package.parent / ".delivery-assets" / version
        self.delivery = delivery if ordinary_directory(delivery) else None
        self.delivery_files = {}
        self.global_assets = global_assets
        self.output = output
        prefix = rf"(?:/{re.escape(code)}(?=/))?"
        self.origins = re.compile(
            rf"(?<![\w/])(?:{'|'.join(map(re.escape, origins))}){prefix}(?=/)",
            re.IGNORECASE,
        ) if origins else None
        self.files = {}
        self.pages = {}
        self.fragments = set()
        self.issues = set()
        self.pending = []
        self.texts = {}
        self.external = external
        self.vendor_files = {}
        self.icon_proofs = None
        omissions = json.loads(ICON_OMISSION_CONFIG.read_text(encoding="utf-8"))
        self.icon_omissions = omissions.get(code, {}).get(version, {})
        self.omitted_icon_links = []

    def package_icon(self, path):
        return (not path.startswith(OTHER_ASSET_ROOTS)
                and source_file(self.package, path.split("/")) is not None)

    def delivery_asset(self, path):
        if (self.delivery is not None and suffix_of(path) in DELIVERY_ASSETS
                and not path.casefold().startswith(OTHER_ASSET_ROOTS)):
            return source_file(self.delivery, path.split("/"))
        return None

    def original_icon_proofs(self):
        if self.icon_proofs is None:
            self.icon_proofs = {}
            for relative, source in walk_files(self.package):
                if suffix_of(relative) not in FRAGMENTS:
                    continue
                raw = source.read_bytes().decode("utf-8-sig", errors="replace")
                icons = IconLinks(raw)
                if icons.has_base:
                    continue
                for href, rel, _, _ in icons.links:
                    value = relative_icon_path(href)
                    if value is not None:
                        target = icon_path_from(relative, value)
                        if self.package_icon(target):
                            self.icon_proofs.setdefault((rel, value), set()).add(target)
        return self.icon_proofs

    def rewrite_icons(self, relative, text):
        icons = IconLinks(text)
        if icons.has_base or not icons.links:
            return text
        start_lines = [0, *(match.end() for match in re.finditer("\n", text))]
        changes = []
        for href, rel, (line, column), raw in icons.links:
            value = relative_icon_path(href)
            if value is None:
                continue
            icon = icon_path_from(relative, value)
            if self.package_icon(icon) or self.delivery_asset(icon):
                continue
            candidates = {icon_path_from(str(parent / "_"), value)
                          for parent in list(PurePosixPath(relative).parents)[1:]}
            candidates = {path for path in candidates if self.package_icon(path)}
            if len(candidates) != 1:
                continue
            target = next(iter(candidates))
            if self.original_icon_proofs().get((rel, value)) != {target}:
                continue
            for attribute in HTML_ATTRIBUTE.finditer(raw, len("<link")):
                if attribute.group(1).lower() != "href":
                    continue
                group = next((index for index in (2, 3, 4) if attribute.group(index) is not None), None)
                if group is None or unescape(attribute.group(group)) != href:
                    break
                attr = attribute.group(group)
                split = min((at for marker in ("?", "#") if (at := attr.find(marker)) >= 0), default=len(attr))
                if unescape(attr[:split]) != urlsplit(href).path:
                    break
                offset = start_lines[line - 1] + column + attribute.start(group)
                changes.append((offset, offset + split, "/" + quote(target)))
                break
        for start, end, replacement in reversed(changes):
            text = text[:start] + replacement + text[end:]
        return text

    def omit_missing_icons(self, relative, text):
        if not self.icon_omissions:
            return text
        icons = IconLinks(text)
        if icons.has_base:
            return text
        start_lines = [0, *(match.end() for match in re.finditer("\n", text))]
        changes = []
        for href, rel, (line, column), raw in icons.links:
            reason = self.icon_omissions.get(href)
            if not reason or not href.startswith("/") or rel not in OPTIONAL_ICON_RELATIONS:
                continue
            path = href[1:]
            if (path.startswith(OTHER_ASSET_ROOTS) or not all(map(safe_name, path.split("/")))
                    or self.package_icon(path) or self.delivery_asset(path)):
                continue
            start = start_lines[line - 1] + column
            changes.append((start, start + len(raw), "".join(re.findall(r"\r?\n", raw))))
            self.omitted_icon_links.append({
                "page": relative, "url": href, "line": line, "column": column + 1,
                "rel": " ".join(sorted(rel)), "reason": reason,
            })
        for start, end, replacement in reversed(changes):
            text = text[:start] + replacement + text[end:]
        return text

    def add_file(self, relative, source, text=None):
        if relative in self.files:
            return
        target = self.output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        suffix = suffix_of(relative)
        delivery_data = source.read_bytes() if self.delivery is not None and source.is_relative_to(self.delivery) else None
        if text is None and suffix in TEXT_ASSETS:
            text = (delivery_data if delivery_data is not None else source.read_bytes()).decode("utf-8-sig")
        if text is None:
            if delivery_data is None:
                shutil.copyfile(source, target)
            else:
                target.write_bytes(delivery_data)
        else:
            if self.origins:
                text = self.origins.sub("", text)
            text = self.external.rewrite(text)
            if relative in self.pages:
                text = self.omit_missing_icons(relative, text)
            target.write_bytes(text.encode("utf-8"))
            self.texts[relative] = text
        self.files[relative] = file_digest(target)
        if delivery_data is not None:
            self.delivery_files[relative] = hashlib.sha256(delivery_data).hexdigest()

    def add_site(self, generated, receipt_root=None):
        for relative, content_digest, text in generated_pages(generated, receipt_root, self.issue):
            self.pages[relative] = content_digest
            self.texts[relative] = self.rewrite_icons(relative, text)
        if not self.pages:
            raise ValueError("站点没有已生成的静态 HTML")
        self.rewrite_home_aliases(generated)
        search_module = spec_from_file_location("prepare_static_search", Path(__file__).with_name("prepare_static_search.py"))
        search_builder = module_from_spec(search_module)
        search_module.loader.exec_module(search_builder)
        search = search_builder.prepare_static_search(self.code, self.version, self.texts)
        if search:
            relative, html = search
            self.add_file(relative, generated / relative, html)
            self.pending.append((relative, relative))
        for relative in self.pages:
            self.add_file(relative, generated / relative, self.texts[relative])
            self.pending.append((relative, relative))
        license_path = "css/Malihu-MIT.txt"
        if self.delivery is not None:
            for relative, source in walk_files(self.delivery):
                if (suffix_of(relative) in DELIVERY_ASSETS - TEXT_ASSETS
                        and relative.casefold() != license_path.casefold()
                        and not relative.casefold().startswith(OTHER_ASSET_ROOTS)):
                    self.add_file(relative, source)
        for relative, source in walk_files(self.package):
            if (suffix_of(relative) in ASSETS - TEXT_ASSETS
                    and not relative.startswith(OTHER_ASSET_ROOTS)):
                self.add_file(relative, source)
        # 随经核对的 malihu 3.0.7 图集附上同一份 MIT 许可。
        sprite = "css/mCSB_buttons.png"
        if self.files.get(sprite) == "e98cac48f5c13b3fbaa28458f0d8f26a78c9d944f8f4edad9abcb249b9028ca7":
            license_source = self.delivery_asset(license_path) or source_file(self.package, license_path.split("/"))
            if (license_source is not None
                    and file_digest(license_source) == "d36dcfcb4f96c9b12084637b1e0951492f0f1175bfd2307aac3a350c821114d5"):
                self.add_file(license_path, license_source)
            else:
                self.issue("missing-resource", sprite, license_path, 1, "滚动条图集缺少经核对的 MIT 许可")
        inspected = set()
        while self.pending:
            relative, document = self.pending.pop()
            if (relative, document) in inspected:
                continue
            inspected.add((relative, document))
            text = self.texts[relative]
            suffix = suffix_of(relative)
            if suffix in FRAGMENTS:
                references = HtmlReferences(text).references
            elif suffix == ".css":
                references = ((url, line, False) for url, line in css_references(text))
            else:
                references = script_references(text)
            for url, row, fragment in references:
                base = relative if suffix in {".css", ".mjs"} else document
                self.resolve(relative, base, document, url, row, fragment)
        self.export_home_aliases(generated)

    def export_home_aliases(self, generated):
        homepage = "index.html"
        if homepage not in self.pages or not self.home_origins:
            return
        occupied = {entry.name.casefold() for entry in generated.iterdir()}
        occupied.update(name.casefold() for name in self.files)
        candidates = [entry.name for entry in self.package.iterdir()
                      if entry.name.casefold() not in occupied and ordinary(entry)
                      and suffix_of(entry.name) in {".htm", ".html", ".asp", ".aspx"}
                      and HOME_ALIAS.fullmatch("/" + entry.name)]
        if not candidates:
            return
        digest = self.pages[homepage]
        size = (generated / homepage).stat().st_size
        roots = [origin for origin in self.home_origins if matches_home(origin, "/", digest, size)]
        for name in candidates:
            if any(matches_home(origin, "/" + name, digest, size) for origin in roots):
                # 使用已重写本地资源的交付首页；原包的同名服务器页面只提供名称。
                self.add_file(name, self.output / homepage)

    def rewrite_home_aliases(self, generated):
        homepage = self.pages.get("index.html")
        home_size = (generated / "index.html").stat().st_size if homepage else 0
        occupied = {entry.name.casefold() for entry in generated.iterdir()}
        roots = {}
        verified = {}
        for page in self.pages:
            text = self.texts[page]
            document = HtmlReferences(text)
            if document.has_base:
                continue
            replacements = []
            start_lines = [0, *(match.end() for match in re.finditer("\n", text))]
            for href, (line, column), tag in document.links:
                try:
                    link = urlsplit(href)
                    if link.scheme or link.netloc or not link.path:
                        continue
                    path = urlsplit(urljoin(f"{DELIVERY_ORIGIN}/{quote(page)}", href)).path
                except ValueError:
                    continue
                decoded_path = unquote(path)
                if not HOME_ALIAS.fullmatch(decoded_path):
                    continue
                if decoded_path[1:].casefold() in occupied:
                    continue
                if path != decoded_path:
                    self.issue("unverified-home-alias", page, href, line,
                               "带百分号编码的旧首页入口需单独核对")
                    continue
                if link.query:
                    self.issue("unverified-home-alias", page, href, line,
                               "带查询参数的旧首页入口需确认参数行为")
                    continue
                if path not in verified:
                    verified[path] = False
                    if homepage:
                        for source in self.home_origins:
                            if source not in roots:
                                roots[source] = matches_home(source, "/", homepage, home_size)
                            if roots[source] and matches_home(source, path, homepage, home_size):
                                verified[path] = True
                                break
                if not verified[path]:
                    self.issue("unverified-home-alias", page, href, line,
                               "无法从本机 CMS 核实此链接与已生成首页内容一致")
                    continue
                tag_end = re.match(r"<\w+", tag).end()
                for attribute in HTML_ATTRIBUTE.finditer(tag, tag_end):
                    if attribute.group(1).lower() != "href":
                        continue
                    group = next((index for index in (2, 3, 4) if attribute.group(index) is not None), None)
                    if group is None or unescape(attribute.group(group)) != href:
                        break
                    raw_href = attribute.group(group)
                    split = min((at for char in "?#" if (at := raw_href.find(char)) >= 0), default=len(raw_href))
                    if unescape(raw_href[:split]) != link.path:
                        break
                    start = start_lines[line - 1] + column + attribute.start(group)
                    replacements.append((start, start + split))
                    break
            if replacements:
                for start, end in reversed(replacements):
                    text = text[:start] + "/" + text[end:]
                self.texts[page] = text

    def resolve(self, page, base, document, url, line, fragment):
        url = url.strip()
        if not url or url.startswith("#"):
            return
        try:
            resolved = urlsplit(urljoin(f"{DELIVERY_ORIGIN}/{quote(base)}", url))
        except ValueError:
            self.issue("missing-resource", page, url, line, "资源地址格式无效")
            return
        if resolved.netloc and resolved.netloc != "delivery.invalid":
            if resolved.hostname in {"localhost", "127.0.0.1", "::1"}:
                self.issue("missing-resource", page, url, line, "本地资源源站未配置同源改写")
            else:
                self.issue("external-dependency", page, url, line, "外域资源未通过本地缓存与摘要锁验证")
            return
        if resolved.scheme not in {"http", "https"}:
            return
        path = unquote(resolved.path).lstrip("/")
        parts = path.split("/")
        suffix = suffix_of(resolved.path)
        if path.startswith(("api/", "__cms/")):
            self.issue("service-dependency", page, url, line, "独立部署时配置同源后端路由")
            return
        if path in self.files:
            if suffix_of(path) in TEXT_ASSETS:
                self.pending.append((path, document))
            return
        if path.startswith(VENDOR_PATH):
            pinned = self.external.paths.get(path)
            if pinned is None:
                self.issue("external-dependency", page, url, line, "同源托管的外域依赖缺少锁定缓存")
                return
            self.add_file(path, pinned["source"])
            self.vendor_files[path] = {key: pinned[key] for key in ("url", "sha256")}
            if path in self.external.notices:
                self.add_file(*self.external.notices[path])
            if suffix in TEXT_ASSETS:
                self.pending.append((path, document))
            return
        if fragment and suffix in FRAGMENTS:
            if resolved.query:
                self.issue("service-dependency", page, url, line, "带查询条件的片段需验证服务行为")
                return
            target = source_file(self.package, parts)
            try:
                if target is None:
                    raise ValueError("指定原包缺少该片段")
                text = read_fragment(target)
            except (UnicodeError, ValueError) as exc:
                self.issue("missing-fragment", page, url, line, str(exc))
                return
            self.fragments.add(path)
            self.add_file(path, target, text)
            self.pending.append((path, document))
            return
        if suffix in ASSETS:
            if path.startswith(("assets/global/", "assets/upload/")):
                root = self.global_assets if parts[1] == "global" else self.package.parent / "assets/upload"
                target = source_file(root, parts[2:]) if ordinary_directory(root) else None
            else:
                target = self.delivery_asset(path) or source_file(self.package, parts)
            if target:
                self.add_file(path, target)
                if suffix in TEXT_ASSETS:
                    self.pending.append((path, document))
                return
        if fragment:
            self.issue("service-dependency", page, url, line, "此请求没有可随包的静态片段")
        else:
            raw = urlsplit(url)
            root_parts = unquote(raw.path).split("/")
            while root_parts and root_parts[0] == "..":
                root_parts.pop(0)
            root_path = "/".join(root_parts)
            reason = "交付目录和公开资源中没有可导出的目标"
            if root_path != path and root_path in self.files:
                reason += f"；原包存在根路径候选 /{root_path}，需确认原页对应关系"
            self.issue("missing-resource", page, url, line, reason)

    def issue(self, kind, page, url, line, reason):
        self.issues.add((kind, page, url, line, reason))

    def manifest(self):
        issue_fields = ("kind", "page", "url", "line", "reason")
        return {
            "siteCode": self.code,
            "packageVersion": self.version,
            "pages": self.pages,
            "files": self.files,
            "fragments": sorted(self.fragments),
            "vendorFiles": self.vendor_files,
            "omittedIconLinks": self.omitted_icon_links,
            **({"deliveryAssets": self.delivery_files} if self.delivery_files else {}),
            "issues": [dict(zip(issue_fields, issue)) for issue in sorted(self.issues)],
        }


def file_digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def matches_home(source, path, digest, size):
    connection = HTTPSConnection if source.scheme == "https" else HTTPConnection
    client = None
    try:
        client = connection(source.hostname, source.port, timeout=3)
        client.request("GET", path, headers={"Accept-Encoding": "identity"})
        with client.getresponse() as response:
            if response.status != 200 or response.headers.get_content_type() != "text/html":
                return False
            content = response.read(size + 1)
            return len(content) == size and hashlib.sha256(content).hexdigest() == digest
    except (OSError, ValueError, HTTPException):
        return False
    finally:
        if client is not None:
            client.close()


def origin(value):
    parts = urlsplit(value.rstrip("/"))
    if (parts.scheme not in {"http", "https"} or not parts.hostname or parts.username
            or parts.password or parts.path or parts.query or parts.fragment):
        raise ValueError("资源源站必须是完整的 HTTP(S) origin")
    return parts.geturl()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True, type=Path, help="CMS 运行目录（读取 settings.yml）")
    parser.add_argument("--site-code", required=True, help="站点编码")
    parser.add_argument("--package-version", required=True, help="静态生成使用的原包版本")
    parser.add_argument("--resource-origin", action="append", default=[],
                        help="改写为同源的原资源服务 origin，可重复指定")
    parser.add_argument("--dependency-lock", type=Path,
                        help="外域资源 URL→SHA256 清单，缓存位于清单同目录 blobs/<SHA256>")
    parser.add_argument("--output", required=True, type=Path, help="新建目录或 .zip 文件")
    args = parser.parse_args()
    try:
        if not SAFE_CODE.fullmatch(args.site_code) or not VERSION.fullmatch(args.package_version):
            raise ValueError("站点编码或原包版本格式不合法")
        runtime = args.runtime.resolve(strict=True)
        static_root, packages, global_assets = settings_paths(runtime)
        generated = static_root / args.site_code
        package = packages / args.site_code / args.package_version
        for source in (generated, package):
            if not ordinary_directory(source):
                raise ValueError(f"源目录不存在或使用了链接：{source}")
        output = args.output.absolute()
        if os.path.lexists(output):
            raise ValueError("输出已经存在，请使用新路径")
        output = output.resolve()
        for root in (runtime, static_root, packages, global_assets):
            if output.is_relative_to(root.resolve()) or root.resolve().is_relative_to(output):
                raise ValueError("输出不能与 CMS 运行目录或公开素材目录重叠")
        origins = sorted({origin(value) for value in args.resource_origin}, key=lambda item: (-len(item), item))
        external = ExternalAssets(args.dependency_lock.absolute() if args.dependency_lock else None)
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".cms-static-", dir=output.parent) as temporary:
            staging = Path(temporary) / "site"
            staging.mkdir()
            export = Export(args.site_code, args.package_version, package, global_assets, staging, origins, external)
            export.add_site(generated, packages / ".workbench-site-model" / args.site_code)
            manifest = export.manifest()
            (staging / "_cms-static-export.json").write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8",
            )
            if output.suffix.lower() == ".zip":
                with ZipFile(output, "x", ZIP_DEFLATED) as archive:
                    for relative, path in walk_files(staging):
                        archive.write(path, relative)
            else:
                staging.rename(output)
        print(f"已导出 {len(export.pages)} 页、{len(export.fragments)} 个片段、"
              f"{len(export.files)} 个文件，发现 {len(export.issues)} 条待核项。")
    except (OSError, ValueError, TypeError, KeyError) as exc:
        parser.exit(1, f"导出失败：{exc}\n")


if __name__ == "__main__":
    main()
