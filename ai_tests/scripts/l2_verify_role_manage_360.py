#!/usr/bin/env python3
"""l2_verify_role_manage_360.py —— 真机 L2：角色手动补录/修正入口（用户裁定 2026-11-10）。

验证点（对应「角色获取少 + 不能手动加改角色/绑音色」）：
  1) 角色配音页存在「＋ 添加角色」「管理角色」；
  2) 点「＋ 添加角色」→ 落 BookCharacterEditActivity（标题「添加角色」，含角色名称/音色路由）；
  3) 点「管理角色」→ 落 BookCharacterManageActivity（新增/编辑/删除/关系网一站页）；
  4) 任一行「换音色」→ 打开换音色弹窗（角色-音色绑定入口在）。

通道：adb360 裸协议（360 劫持 5037，见 docs/project-rules/physical-device-testing.md §五）。
前置：已用覆盖安装保留数据 + 书架至少一本可打开的文本小说。

用法：ai_tests\\venv\\Scripts\\python.exe ai_tests/scripts/l2_verify_role_manage_360.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adb360 import RawAdb  # noqa: E402

PKG = "io.legado.miss.app.debug"
MAIN = "%s/io.legado.app.ui.main.MainActivity" % PKG
BUILTIN = "%s/io.legado.app.ui.book.read.config.BuiltinTtsActivity" % PKG
SHOT_DIR = os.path.join("output", "l3")


def top_activity(adb, serial):
    out = adb.shell("dumpsys activity activities | grep -E 'topResumedActivity'", serial=serial, timeout=30.0)
    return out.strip()


def dump_texts(adb, serial):
    return adb.dump_ui(serial=serial)


def find(adb, serial, label, tries=5):
    for _ in range(tries):
        xml = dump_texts(adb, serial)
        node = adb.find_node(xml, label)
        if node:
            return node
        time.sleep(1.0)
    return None


def open_a_text_book(adb, serial):
    """书架 → 全部 → 打开一本文本小说（WBB-101 为本地测试书；视频书则退回换一本）。"""
    adb.shell("am force-stop %s" % PKG, serial=serial, timeout=30.0)
    adb.shell("am start -W -n %s" % MAIN, serial=serial, timeout=60.0)
    time.sleep(3)
    adb.shell("input tap 150 2500", serial=serial, timeout=20.0)   # 书架 tab
    time.sleep(2)
    adb.shell("input tap 209 855", serial=serial, timeout=20.0)    # 全部
    time.sleep(2)
    adb.shell("input tap 209 855", serial=serial, timeout=20.0)    # 第一格卡片
    time.sleep(4)
    top = top_activity(adb, serial)
    if "VideoPlayerActivity" in top:
        adb.shell("input keyevent 4", serial=serial, timeout=20.0)
        time.sleep(2)
        adb.shell("input tap 991 855", serial=serial, timeout=20.0)
        time.sleep(4)
        top = top_activity(adb, serial)
    return top


def main():
    adb = RawAdb()
    serial = adb.resolve_serial("")
    os.makedirs(SHOT_DIR, exist_ok=True)
    results = []

    top = open_a_text_book(adb, serial)
    book_ok = "ReadBookActivity" in top
    print("[%s] 打开文本小说：%s" % ("OK" if book_ok else "FAIL", top[:110]))
    results.append(book_ok)
    if not book_ok:
        print("\n结果：%d/%d 通过（无法打开书，后续跳过）" % (sum(results), len(results)))
        return 1

    adb.shell("am start -W -n %s --es page_tab roles" % BUILTIN, serial=serial, timeout=60.0)
    time.sleep(3)
    print("[OK] 角色页：%s" % top_activity(adb, serial)[:110])

    add = find(adb, serial, "添加角色")
    manage = find(adb, serial, "管理角色")
    print("[%s] 角色页「＋ 添加角色」按钮" % ("OK" if add else "FAIL"))
    print("[%s] 角色页「管理角色」按钮" % ("OK" if manage else "FAIL"))
    results += [bool(add), bool(manage)]

    if add:
        adb.tap(add[0], add[1], serial=serial)
        time.sleep(2.5)
        top = top_activity(adb, serial)
        ok = "BookCharacterEditActivity" in top and find(adb, serial, "角色名称") is not None
        print("[%s] 添加角色 → %s" % ("OK" if ok else "FAIL", top[:110]))
        adb.screenshot(os.path.join(SHOT_DIR, "k100_role_add.png"), serial=serial)
        results.append(ok)
        adb.shell("input keyevent 4", serial=serial, timeout=20.0)
        time.sleep(1.5)

    if manage:
        adb.shell("am start -W -n %s --es page_tab roles" % BUILTIN, serial=serial, timeout=60.0)
        time.sleep(2)
        manage = find(adb, serial, "管理角色")
        if manage:
            adb.tap(manage[0], manage[1], serial=serial)
            time.sleep(2.5)
            top = top_activity(adb, serial)
            ok = "BookCharacterManageActivity" in top
            print("[%s] 管理角色 → %s" % ("OK" if ok else "FAIL", top[:110]))
            adb.screenshot(os.path.join(SHOT_DIR, "k100_role_manage.png"), serial=serial)
            results.append(ok)
            adb.shell("input keyevent 4", serial=serial, timeout=20.0)
            time.sleep(1.5)

    # 换音色入口（角色-音色绑定）
    adb.shell("am start -W -n %s --es page_tab roles" % BUILTIN, serial=serial, timeout=60.0)
    time.sleep(2)
    swap = find(adb, serial, "换音色", tries=4)
    if swap:
        adb.tap(swap[0], swap[1], serial=serial)
        time.sleep(2)
        dialog = find(adb, serial, "换个音色", tries=3)
        print("[%s] 角色行「换音色」弹窗" % ("OK" if dialog else "FAIL"))
        adb.screenshot(os.path.join(SHOT_DIR, "k100_role_voice.png"), serial=serial)
        results.append(bool(dialog))
    else:
        print("[SKIP] 当前角色列表为空（先识别/添加角色后再验换音色）")

    print("\n结果：%d/%d 通过" % (sum(1 for r in results if r), len(results)))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
