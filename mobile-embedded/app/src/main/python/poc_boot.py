# -*- coding: utf-8 -*-
"""Android 嵌入模式启动胶水。

复用主仓库 web_server 模块级构建好的 aiohttp 应用(web_server.app,
其服务运行逻辑在 ``__main__`` guard 里,导入侧无副作用),以 AppRunner
方式绑定 127.0.0.1 回环地址,供 WebView 与原生客户端访问。

STATE 供宿主 Activity 轮询启动进度;failed 时 error 携带完整 traceback,
避免真机上没有控制台时无法定位 Python 侧故障。
"""
import os
import sys
import threading
import traceback

HOST = "127.0.0.1"
PORT = 18000

STATE = {"status": "starting", "error": ""}


def get_state() -> dict:
    return dict(STATE)


def start(app_root: str) -> None:
    app_root = os.path.abspath(app_root)
    sys.path.insert(0, app_root)
    os.chdir(app_root)
    os.environ["DICEFRAME_EMBEDDED"] = "1"
    threading.Thread(target=_serve, name="diceframe-embedded", daemon=True).start()


def _serve() -> None:
    import asyncio

    try:
        STATE["status"] = "importing-backend"
        import web_server

        from aiohttp import web

        from src.runtime_logging import RETENTION_DAYS, configure_runtime_logging

        STATE["status"] = "starting-server"
        log_path = configure_runtime_logging(web_server.DATA_DIR)
        web_server.logger.info("运行日志写入 %s（保留 %s 天）", log_path, RETENTION_DAYS)

        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        runner = web.AppRunner(web_server.app, access_log=None)
        loop.run_until_complete(runner.setup())
        site = web.TCPSite(runner, HOST, PORT)
        loop.run_until_complete(site.start())
        STATE["status"] = "ready"
        web_server.logger.info("DiceFrame 嵌入服务已启动:http://%s:%s", HOST, PORT)
        loop.run_forever()
    except BaseException:
        STATE["status"] = "failed"
        STATE["error"] = traceback.format_exc()
        traceback.print_exc()
        raise
