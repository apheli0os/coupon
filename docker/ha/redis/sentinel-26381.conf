# 哨兵模板：启动脚本会把 __MASTER_HOST__ 替换为动态解析的主节点 IP
port 26379
bind 0.0.0.0
protected-mode no
sentinel monitor mallredis __MASTER_HOST__ 6379 2
sentinel auth-pass mallredis mallRedis2026
sentinel down-after-milliseconds mallredis 5000
sentinel failover-timeout mallredis 10000
sentinel parallel-syncs mallredis 1
