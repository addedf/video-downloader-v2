# -*- coding: utf-8 -*-
"""X guest 客户端：免登录 guest token + GraphQL 读接口。

只覆盖读（单推/用户时间线），登录流不在本层（阶段 3 再接）。
阶段 0 实测（2026-09-28）：guest activate + TweetResultByRestId 直连 200，
不需要 x-client-transaction-id。

传输层用 httpx 同步客户端（Chaquopy 已有依赖），桌面端同一套代码可跑。
"""
import json
import threading
import time
from pathlib import Path

import httpx

_STATIC_DIR = Path(__file__).resolve().parent / "static"

# X 网页端公开 Bearer（公开常量，非用户凭据）。
BEARER = (
    "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D"
    "1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
)
UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36"
)
GRAPHQL_HOST = "https://x.com"
API_HOST = "https://api.x.com"


def _load_operations() -> dict:
    with open(_STATIC_DIR / "graphql.json", encoding="utf-8") as fp:
        return json.load(fp)["operations"]


OPERATIONS = _load_operations()


class GraphQLError(Exception):
    """GraphQL 200 + errors 且无 data 时抛（X 的失败不一定走 HTTP 状态码）。"""

    def __init__(self, operation: str, errors, payload=None):
        self.operation = operation
        self.errors = errors or []
        self.payload = payload
        text = "; ".join(
            f"{item.get('code')}: {item.get('message')}" for item in self.errors
        ) or "unknown graphql error"
        super().__init__(f"{operation}: {text}")


class XGuestClient:
    """guest 会话：activate + graphql 读。

    guest_token 进程内缓存；401/403 时重激活一次再试（guest token 会过期）。
    """

    def __init__(self, timeout: float = 15.0):
        self._client = httpx.Client(
            timeout=timeout,
            headers={
                "user-agent": UA,
                "accept": "*/*",
                "accept-language": "en-US,en;q=0.9",
            },
            follow_redirects=False,
        )
        self._guest_token = ""
        self._guest_activated_at = 0.0
        self._lock = threading.Lock()

    # ---- guest token ------------------------------------------------------ #

    def guest_token(self, force: bool = False) -> str:
        with self._lock:
            now = time.time()
            if not force and self._guest_token and now - self._guest_activated_at < 900:
                return self._guest_token
            response = self._client.post(
                f"{API_HOST}/1.1/guest/activate.json",
                headers={"authorization": BEARER},
            )
            response.raise_for_status()
            self._guest_token = response.json()["guest_token"]
            self._guest_activated_at = now
            return self._guest_token

    def _headers(self, token: str) -> dict:
        return {
            "authorization": BEARER,
            "x-guest-token": token,
            "x-twitter-active-user": "yes",
            "x-twitter-client-language": "en",
            "content-type": "application/json",
        }

    # ---- GraphQL ----------------------------------------------------------- #

    def _request(self, operation_name: str, variables: dict,
                 use_post: bool = False) -> dict:
        operation = OPERATIONS.get(operation_name)
        if not operation:
            raise ValueError(f"注册表里没有操作：{operation_name}")
        token = self.guest_token()
        url = f"{GRAPHQL_HOST}/i/api/graphql/{operation['queryId']}/{operation_name}"
        features = operation.get("featureSwitches") or {}

        def _send(current_token: str):
            headers = self._headers(current_token)
            if use_post:
                body = {"variables": variables, "queryId": operation["queryId"]}
                if features:
                    body["features"] = features
                return self._client.post(url, json=body, headers=headers)
            params = {"variables": json.dumps(variables, separators=(",", ":"))}
            if features:
                params["features"] = json.dumps(features, separators=(",", ":"))
            return self._client.get(url, params=params, headers=headers)

        response = _send(token)
        if response.status_code in (401, 403, 404):
            # guest token 过期/被拒：重激活一次再试
            token = self.guest_token(force=True)
            response = _send(token)
        response.raise_for_status()
        payload = response.json()
        errors = payload.get("errors")
        if errors and not payload.get("data"):
            raise GraphQLError(operation_name, errors, payload)
        return payload

    # ---- 业务读接口 ---------------------------------------------------------- #

    def tweet_result(self, tweet_id: str) -> dict:
        """单推详情（TweetResultByRestId），返回 data.tweetResult.result（可能为空）。"""
        payload = self._request(
            "TweetResultByRestId",
            {
                "tweetId": str(tweet_id),
                "withCommunity": False,
                "includePromotedContent": False,
                "withVoice": False,
            },
        )
        data = payload.get("data") or {}
        return (data.get("tweetResult") or {}).get("result") or {}

    def user_tweets(self, user_id: str, count: int = 20,
                    cursor: str | None = None) -> dict:
        """用户原创时间线（guest 可用，阶段 3 批量下载用）。"""
        variables = {
            "userId": str(user_id),
            "count": count,
            "includePromotedContent": True,
            "withQuickPromoteEligibilityTweetFields": True,
            "withVoice": True,
        }
        if cursor:
            variables["cursor"] = cursor
        return self._request("UserTweets", variables)

    def close(self):
        self._client.close()
