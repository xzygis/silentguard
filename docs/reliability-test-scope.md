# 测试范围与缺陷分析

Step1：完成；TMP_ROOT 沿用本会话 prepare_test.sh 输出。EXEC_SOURCE 为空。
Step2：完成；LANG=kotlin，app 模块，JUnit4，本地 JVM；无 AGENTS/CLAUDE 单测约定。
业务规约：docs/reliability-upgrade.md。没有可复用 fixture 或 mock。
为完成真实 SQLite 迁移验收，引入 Robolectric；不模拟 DAO 的 SQL 返回结果。

## Step3 / TARGETS

scope_type: diff。本地未提交及新增文件，排除 Compose、入口组装和纯实体声明。
下面路径均相对 app/src/main/java/com/xzygis/silentguard/，locator 为类与方法签名。
新增文件 source=untracked，hunks=[]；修改文件 source=diff_hunk，采用 git diff --unified=0 定位。

| file_path | target_type / symbol（locator） | source | reason |
|---|---|---|---|
| config/ConfigValidation.kt | method ConfigValidation.errors(MonitorConfig) | untracked | 数值与邮箱边界 |
| data/SmsIdentity.kt | method SmsIdentity.key(String,String,String,Long) | untracked | 重启与重复事件身份 |
| data/SmsIdentity.kt | method SmsIdentity.matchesNotification(String,String,Long,Long) | untracked | 通知匹配时间窗口 |
| data/AppDatabase.kt | method MIGRATION_1_2.migrate / MIGRATION_2_3.migrate / MIGRATION_3_4.migrate | diff_hunk | 旧版本保留与 Room 校验 |
| data/MonitorEventDao.kt | method insert / getPendingLocationEvents / bindOutbox / markDelivered | diff_hunk | 批次绑定、去重、投递后状态 |
| data/MonitorEventDao.kt | method observePage / exportPage / deleteDeliveredBefore | diff_hunk | 分页排序和未投递保护 |
| data/OutboxMessage.kt | method OutboxDao.claim / finish / retryFailed | untracked | 并发领取、租约恢复、手动重试 |
| mail/OutboxDelivery.kt | method OutboxDelivery.deliver(String,String) | untracked | 开关、实际发送、有限重试 |
| data/DataMaintenance.kt | method DataExport.encode(MonitorEvent) | untracked | 导出正文转义与字段完整性 |
| location/LocationPolicy.kt | method isFresh / nextDelayMillis / shouldAlert | untracked | 保留此前定位回归 |
| mail/SmtpPropertiesBuilder.kt | method build(String,Int) | diff_hunk | SMTP 网络超时和服务器身份校验 |
| mail/MailSubject.kt | method withDevicePrefix(String,String,String) | untracked | 所有邮件统一机型前缀且不重复 |

## Step4 / BUG_MAP

BUG_MAP: []

候选过滤摘要：

- 跨天误标 SENT：旧实现只展示今日却标记全部；当前仅选取未绑定批次，并在投递成功事务中更新关联事件，存量问题已修复。
- 入队即 SENT、通知重复退回内容：旧路径已删除，当前 SMS 事件与任务同一事务，唯一来源键冲突不继续创建任务。
- 保存覆盖守护：当前 saveConfig 已不写守护字段。
- SMTP 成功与 Room 提交存在崩溃窗口：两个系统不能原子提交，规约已明确结果不确定，稳定 Message-ID 只辅助去重；不承诺 exactly-once。
- 并发任务读到旧尝试数：领取后重新读取数据库中的 attempts，最终失败按最新计数判定。
- 导出期间不断新增记录：已冻结最大 ID，有限分页；不是无限尾随导出。

Step5 按声明容器串行生成、执行和修复。系统/OEM 生命周期和真实 SMTP 可达性列为设备验收，不把 JVM 测试通过描述为真机验证。

### Step5 已执行结果

共同命令：`./gradlew testDebugUnitTest --tests '<完整测试类名>' -Pkotlin.compiler.execution.strategy=in-process --console=plain`。

- ConfigValidationTest：2 个测试通过，首次执行成功。
- SmsIdentityTest：2 个测试通过，首次执行成功。
- AppDatabaseMigrationTest：1 个测试覆盖 v1/v2/v3 三条升级路径，最终通过；首轮为 Robolectric 用户目录下载锁权限错误（test-infra-error），改为 app/build/test-home；第二轮 DAO 变更与编译同时发生导致 kapt 产物不同步（compile-error），稳定源码后第三轮通过。日志 build/migration-test.log。
- MonitorEventDaoTest：6 个测试通过，首次执行成功。跨天批次、唯一来源、事务回滚、未发送清理保护、分页/导出上界、批次容量。日志 build/dao-test.log。
- OutboxDeliveryTest：最初 6 个测试通过；补强租约所有权、未知结果重试上限后，8 个测试全部通过。验证暂停不消耗次数、手动发送、仅关联事件变为 SENT、冻结大正文与收件人、四次失败停止、数据库关闭重开恢复、取消传播、竞争及过期回调。日志 build/outbox-test.log。
- DataExportTest：2 个测试首次通过，覆盖换行、引号、反斜杠、控制字符、空值、数值精度及字段完整性。日志 build/export-test.log。
- SmtpPropertiesBuilderTest：保留 2 个历史测试，新增 1 个测试，3 个全部通过。覆盖连接/读/写超时及所有端口的服务器身份检查。日志 build/smtp-test.log。
- MailSubjectTest：3 个测试全部通过，覆盖添加厂商/机型、已有前缀幂等、空厂商及空设备信息降级。
- 本次迭代新增 25 个用例，保留 8 个原有用例（含此前华为修复的 LocationPolicyTest），全量共 33 个。
- 定向测试命令均退出 0，最终 all-pass；未删除或放宽断言。业务修复由用户“完成所有优化项”的授权执行，测试不把故障行为当作正确预期。

## Step6 / 覆盖率

cov_config=null，CHECK_TARGETS=TARGETS，CHECK_COV_MODE=skip。
项目未配置覆盖率卡口，用户未指定覆盖率阈值，EXEC_SOURCE 为空；依技能规则跳过统计。
没有输出或推算覆盖率百分比；测试通过率不能代替代码覆盖率。

## Step7 / 全量验收

最终命令：`./gradlew assembleDebug testDebugUnitTest lintDebug assembleRelease -Pkotlin.compiler.execution.strategy=in-process --console=plain`。
结果记录于 docs/reliability-upgrade.md 与 build/reliability-verification.log。
测试 HTML：app/build/reports/tests/testDebugUnitTest/index.html。
