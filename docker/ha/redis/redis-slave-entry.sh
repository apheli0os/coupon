#!/bin/sh
# 与主节点/哨兵保持一致的 IP 标识，避免哨兵发现不到从节点
set -e
TPL=/etc/redis/redis.conf.tpl
CONF=/tmp/redis.conf

until redis-cli -h redis-master -p 6379 -a mallRedis2026 --no-auth-warning ping 2>/dev/null | grep -q PONG; do
  echo "waiting for redis-master..."
  sleep 2
done

MASTER_IP=$(getent hosts redis-master | awk '{print $1}' | head -1)
SLAVE_IP=$(getent hosts $(hostname) | awk '{print $1}' | head -1)
if [ -z "$SLAVE_IP" ]; then
  SLAVE_IP=$(hostname -i | awk '{print $1}')
fi
cp "$TPL" "$CONF"
sed -i "s/__MASTER_IP__/$MASTER_IP/; s/__SLAVE_IP__/$SLAVE_IP/" "$CONF"
echo "slave $SLAVE_IP replicating to $MASTER_IP"
exec redis-server "$CONF"
