# -*- coding: utf-8 -*-
"""free_layout_paging_srv — 自由布局分页 E2E 本地服务

用途：验证 articleStyle=5 自由布局「上滑到底自动加载下一页」触发链路
（RssArticlesFragment.onScrolled → scrollToBottom → viewModel.loadMore → ruleNextPage）。

配合 free_layout_paging_src.json 导入订阅源：
  sortUrl = 首页:http://127.0.0.1:18093/list?page=1
  ruleNextPage = css:a.next-page@href

启动（外部，不在本脚本内常驻）：
  ai_tests\\venv\\Scripts\\python.exe ai_tests\\testdata\\free_layout_paging_srv\\server.py
需 adb reverse tcp:18093 tcp:18093（模拟器内 127.0.0.1:18093 → 宿主机）。
"""
from http.server import BaseHTTPRequestHandler, HTTPServer

ITEMS_PER_PAGE = 20
MAX_PAGE = 50

PAGE_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>free-layout-paging</title></head>
<body>
<div class="article-list">
{items}
</div>
<a class="next-page" href="/list?page={next_page}">next</a>
</body></html>"""

ITEM_HTML = """<div class="item">
<div class="title">p{page}-i{i:02d}</div>
<div class="link">/a/{page}/{i}</div>
<div class="image"></div>
<div class="pubdate">2026-09-11 22:00</div>
<div class="description">desc</div>
</div>"""


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/favicon"):
            self.send_response(404)
            self.end_headers()
            return
        # 非 /list 路径（含源根路径）统一回落到第 1 页，避免 404 打断源的初始化
        page = 1
        if "page=" in self.path:
            try:
                page = int(self.path.split("page=")[1].split("&")[0])
            except ValueError:
                page = 1
        page = max(1, min(page, MAX_PAGE))
        items = "\n".join(
            ITEM_HTML.format(page=page, i=i) for i in range(1, ITEMS_PER_PAGE + 1)
        )
        body = PAGE_HTML.format(items=items, next_page=page + 1).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("[srv]", fmt % args)


if __name__ == "__main__":
    print("serving 127.0.0.1:18093")
    HTTPServer(("127.0.0.1", 18093), Handler).serve_forever()
