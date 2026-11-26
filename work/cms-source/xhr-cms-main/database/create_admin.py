"""为新建基础库生成管理员 SQL；不连接数据库、不保存明文密码。"""
import argparse
import base64
import getpass
import hashlib
import os
from pathlib import Path
import secrets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="新 SQL 文件路径；不会覆盖现有文件")
    args = parser.parse_args()
    password = getpass.getpass("设置本地 supera 密码（至少 12 位）：")
    if len(password) < 12 or password != getpass.getpass("再次输入密码："):
        raise SystemExit("密码长度不足或两次输入不一致，未生成文件。")
    salt = secrets.token_bytes(16)
    pepper = os.environ.get("CMS_PASSWORD_PEPPER", "").encode("utf-8")
    raw = password.encode("utf-8")
    digest = hashlib.new("sm3", salt + pepper + raw).digest()
    for _ in range(119999):
        digest = hashlib.new("sm3", digest + salt + pepper + raw).digest()
    encode = lambda value: base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")
    encoded = "{SM3}v1$120000$" + encode(salt) + "$" + encode(digest)
    sql = (
        "-- 仅用于空基础库；已有 ID 或账号时失败，不覆盖既有用户。\n"
        "SET NAMES utf8mb4;\n"
        "START TRANSACTION;\n"
        "INSERT INTO t_usergroups (id,title,enabled,deleted,site_id,group_setting) "
        "VALUES (1,'平台管理员',1,0,0,'');\n"
        "INSERT INTO t_users (id,username,password,screen_name,activated,group_id,deleted) "
        "VALUES (13,'supera','" + encoded + "','平台管理员',0,1,0);\n"
        "COMMIT;\n"
    )
    with args.output.open("x", encoding="utf-8") as target:
        target.write(sql)
    print("已生成管理员 SQL；请仅在新建基础库中审核执行。")


if __name__ == "__main__":
    main()
