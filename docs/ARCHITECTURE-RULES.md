# 架构铁律(CI 强制,不靠自觉)

## 铁律 1:独立 schema,禁止跨域 join
每个领域独占一个 PostgreSQL schema,并使用只授权本域的 DB 用户。
跨域读写会被数据库以 `permission denied` 拒绝。
守卫:`SchemaIsolationTests`

## 铁律 2:只走接口/事件
跨域交互只能通过 `<domain>/api` 包下的接口与事件。
`<domain>/internal` 包对外不可见,直接引用会导致 `ModularityTests` 失败。
守卫:`ModularityTests`(Spring Modulith `verify()`)

## 铁律 3:数据自持
需要他域数据时订阅其领域事件,在本域维护只读副本。
禁止为"查得快"去读他域的表——铁律 1 会挡住,但更要在设计上自觉。

## 为什么
上一代系统 12 个模块共用一个库、195 个服务塞在一起,改一处炸一片。
这三条规则是 v1.0 与它的根本分野。规则靠机器守,不靠人。
