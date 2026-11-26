"""把已审核的站点搜索表单和本次正式页面一起导出。"""

import json
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import quote


DEPENDENCIES = Path(__file__).with_name("static-dependencies")
MARKER = '<form class="search_wrap" action="/__cms/search" method="get" data-cms-header-search>'
DESTINATION = "other/search.html"
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}
SKIP = {"footer", "header", "nav", "script", "style", "template"}
NAVIGATION = {"public_navbox", "release-tabs", "page"}


class PublishedText(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.stack = []
        self.text = []
        self.headings = []
        self.feed(html)
        self.close()

    def handle_starttag(self, tag, attrs):
        if tag in VOID:
            return
        attributes = dict(attrs)
        classes = set((attributes.get("class") or "").split())
        main = tag == "main" or "main" in classes
        skipped = tag in SKIP or bool(classes & NAVIGATION) or "hidden" in attributes or (
            attributes.get("aria-hidden") == "true" or attributes.get("aria-disabled") == "true"
        )
        heading = None
        if tag in {"h1", "h2", "h3"} and any(frame[1] for frame in self.stack):
            heading = []
            self.headings.append(heading)
        self.stack.append((tag, main, skipped, heading))

    def handle_endtag(self, tag):
        for position in range(len(self.stack) - 1, -1, -1):
            if self.stack[position][0] == tag:
                del self.stack[position:]
                break

    def handle_data(self, data):
        text = " ".join(data.split())
        if not text or not any(frame[1] for frame in self.stack) or any(frame[2] for frame in self.stack):
            return
        self.text.append(text)
        for _, _, _, heading in reversed(self.stack):
            if heading is not None:
                heading.append(text)
                break


def prepare_static_search(site, version, texts):
    config = DEPENDENCIES / "reviewed-header-search.json"
    if not config.is_file():
        return None
    approval = json.loads(config.read_text(encoding="utf-8")).get(site, {}).get(version)
    if not approval:
        return None
    if DESTINATION in texts:
        raise ValueError("搜索结果页与已有正式页面地址冲突")
    routes = set(approval["routes"])
    documents = []
    for relative, html in texts.items():
        route = "/" + relative
        occurrences = html.count(MARKER)
        if route in routes:
            if occurrences != 1:
                raise ValueError(f"搜索表单原生生成结果缺失：{relative}")
            texts[relative] = html.replace(MARKER, MARKER.replace("/__cms/search", "/" + DESTINATION), 1)
        elif occurrences:
            raise ValueError(f"搜索表单来源未审核：{relative}")
        if route not in routes or route in approval.get("aliases", []):
            continue
        page = PublishedText(html)
        heading = next((" ".join(words) for words in page.headings if words), "")
        title = approval.get("titles", {}).get(route, heading or relative)
        documents.append({"path": "/" if route == "/index.html" else "/" + quote(relative),
                          "title": title, "text": " ".join(page.text)})
    payload = json.dumps(documents, ensure_ascii=False, separators=(",", ":")).replace("<", r"\u003c")
    shell = (DEPENDENCIES / "search" / "results.html").read_text(encoding="utf-8")
    return DESTINATION, shell.replace("__SEARCH_DOCUMENTS__", payload)
