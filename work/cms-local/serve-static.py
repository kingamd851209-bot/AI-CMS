"""在回环地址预演独立静态交付和同源公开表单。"""

from argparse import ArgumentParser, ArgumentTypeError
import http.client
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
from urllib.parse import unquote


FORM_PREFIX = "/api/form/public/"
FORM_ROUTES = {
    FORM_PREFIX + "definition": "GET",
    FORM_PREFIX + "captcha": "GET",
    FORM_PREFIX + "script.js": "GET",
    FORM_PREFIX + "submit": "POST",
}
BODY_LIMIT = 65_536
RESPONSE_LIMIT = 2 * 1024 * 1024


def loopback_origin(value):
    match = re.fullmatch(r"http://(?:127\.0\.0\.1|localhost):([0-9]{1,5})/?", value)
    if not match or not 1 <= int(match[1]) <= 65535:
        raise ArgumentTypeError("表单服务地址须为 http://127.0.0.1:<端口>")
    return int(match[1])


class PreviewServer(ThreadingHTTPServer):
    # 浏览器并行请求页面、样式和图片时，Windows 默认的短连接等待队列容易溢出。
    request_queue_size = max(128, ThreadingHTTPServer.request_queue_size)


class PreviewHandler(SimpleHTTPRequestHandler):
    def serve(self):
        port = self.server.server_port
        hosts = self.headers.get_all("Host", [])
        loopback = r"(?:127\.0\.0\.1|(?:[a-z0-9-]+\.)*localhost)"
        suffix = r"(?::80)?" if port == 80 else f":{port}"
        if len(hosts) != 1 or not re.fullmatch(loopback + suffix, hosts[0]):
            return self.error_json(400, "请使用本机预演地址访问")

        path, separator, query = self.path.partition("?")
        method = FORM_ROUTES.get(path)
        if method is not None:
            if self.command != method:
                return self.error_json(405, "公开表单路由不支持此请求方法", allow=method)
            if self.server.form_port is None:
                return self.error_json(503, "未配置公开表单服务，请提供 --form-origin")
            if path.endswith(("/definition", "/captcha")):
                if not re.fullmatch(r"categoryId=[1-9][0-9]{0,9}", query):
                    return self.error_json(400, "请提供一个有效的表单分类 categoryId")
            elif separator:
                return self.error_json(400, "此公开表单路由不接受查询参数")
            return self.proxy()

        decoded = unquote(path).replace("\\", "/")
        if decoded.lstrip("/").casefold().startswith("api/") or not path.startswith("/"):
            return self.error_json(404, "预演服务仅开放指定的公开表单接口")
        if self.command == "GET":
            return super().do_GET()
        if self.command == "HEAD":
            return super().do_HEAD()
        return self.error_json(405, "静态资源仅支持 GET 和 HEAD", allow="GET, HEAD")

    do_GET = do_HEAD = do_POST = do_OPTIONS = do_PUT = do_PATCH = do_DELETE = serve

    def send_head(self):
        root = Path(self.directory)
        try:
            requested = Path(self.translate_path(self.path))
            if not requested.resolve().is_relative_to(root):
                return self.error_json(404, "静态资源不存在")
            if requested.is_dir():
                for name in ("index.html", "index.htm"):
                    index = requested / name
                    if index.is_file() and not index.resolve().is_relative_to(root):
                        return self.error_json(404, "静态资源不存在")
        except (OSError, RuntimeError, ValueError):
            return self.error_json(404, "静态资源不存在")
        return super().send_head()

    def guess_type(self, path):
        # 导出目录中的 ASP 后缀文件为已渲染 HTML 或经过原包检查的静态片段。
        if Path(path).suffix.lower() in (".aspx", ".asp"):
            return "text/html; charset=utf-8"
        return super().guess_type(path)

    def list_directory(self, path):
        return self.error_json(404, "目录没有可访问的首页")

    def proxy(self):
        form_port = self.server.form_port
        headers = {}
        body = None
        if self.command == "POST":
            if self.headers.get_all("Origin", []) != ["http://" + self.headers["Host"]]:
                return self.error_json(403, "只接受当前静态预演页面的同源提交")
            if self.headers.get("Transfer-Encoding"):
                return self.error_json(400, "表单提交需要固定请求体长度")
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
                return self.error_json(411, "表单提交需要有效的 Content-Length")
            length = int(lengths[0])
            if length > BODY_LIMIT:
                return self.error_json(413, "表单提交不能超过 64 KB")
            if self.headers.get_content_type() != "application/json":
                return self.error_json(415, "表单提交需要 application/json")
            try:
                self.connection.settimeout(10)
                body = self.rfile.read(length)
            except OSError:
                return self.error_json(408, "读取表单提交超时")
            if len(body) != length:
                return self.error_json(400, "表单请求体不完整")
            headers = {
                "Content-Type": "application/json",
                "Origin": f"http://127.0.0.1:{form_port}",
            }

        upstream = http.client.HTTPConnection("127.0.0.1", form_port, timeout=10)
        try:
            upstream.request(self.command, self.path, body=body, headers=headers)
            response = upstream.getresponse()
            if 300 <= response.status < 400:
                return self.error_json(502, "公开表单服务返回重定向，请检查服务地址")
            output = response.read(RESPONSE_LIMIT + 1)
            if len(output) > RESPONSE_LIMIT:
                return self.error_json(502, "公开表单服务响应超过预演限制")
            status = response.status
            content_type = response.getheader("Content-Type", "application/octet-stream")
        except (OSError, http.client.HTTPException):
            return self.error_json(502, "公开表单服务不可用，请检查 --form-origin")
        finally:
            upstream.close()
        self.respond(status, output, content_type)

    def error_json(self, status, message, allow=None):
        body = json.dumps({"code": status, "message": message}, ensure_ascii=False).encode("utf-8")
        self.respond(status, body, "application/json; charset=utf-8", allow)

    def respond(self, status, body, content_type, allow=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if allow:
            self.send_header("Allow", allow)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path, help="独立静态交付目录")
    parser.add_argument("--port", required=True, type=int, help="本机预演端口")
    parser.add_argument("--form-origin", type=loopback_origin, help="受控本机公开表单源站")
    args = parser.parse_args()
    root = args.directory.resolve()
    if not root.is_dir():
        parser.error("静态交付目录不存在")
    if not 1 <= args.port <= 65535:
        parser.error("预演端口必须在 1 至 65535 之间")
    if args.port == args.form_origin:
        parser.error("表单服务端口不能与静态预演端口相同")

    def handler(*request):
        return PreviewHandler(*request, directory=str(root))

    with PreviewServer(("127.0.0.1", args.port), handler) as server:
        server.form_port = args.form_origin
        print(f"静态预演：http://127.0.0.1:{args.port}，目录 {root}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
