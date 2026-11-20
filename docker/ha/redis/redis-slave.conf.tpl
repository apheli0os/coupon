# Redis 从节点模板：启动脚本把 __MASTER_IP__ 替换为主节点 IP
bind 0.0.0.0
protected-mode no
port 6379
appendonly yes
appendfsync everysec
requirepass mallRedis2026
masterauth mallRedis2026
maxmemory 256mb
maxmemory-policy allkeys-lru
replicaof __MASTER_IP__ 6379
# 从节点 announce 用容器 IP/端口，让同网络内的哨兵能发现它
replica-announce-ip __SLAVE_IP__
replica-announce-port 6379
