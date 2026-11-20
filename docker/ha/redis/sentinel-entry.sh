#!/bin/sh
# 等待主节点就绪后，把动态解析到的 IP 写入 sentinel 配置，
# 规避 Redis 7 启动时自身 DNS 解析主机名失败的问题
set -e
TPL=/etc/redis/sentinel.conf.tpl
CONF=/tmp/sentinel.conf

until redis-cli -h redis-master -p 6379 -a mallRedis2026 --no-auth-warning ping 2>/dev/null | grep -q PONG; do
  echo "waiting for redis-master to be ready..."
  sleep 2
done

MASTER_IP=$(getent hosts redis-master | awk '{print $1}' | head -1)
echo "redis-master resolved to $MASTER_IP"

cp "$TPL" "$CONF"
sed -i "s/__MASTER_HOST__/$MASTER_IP/" "$CONF"
grep "sentinel monitor" "$CONF"

echo "starting sentinel"
exec redis-sentinel "$CONF"
