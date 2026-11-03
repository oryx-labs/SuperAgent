编译成功（`-q` 静默模式下无错误输出即通过）。

## 交付总结

**改动文件**：`superagent-web/src/main/java/com/superagent/web/GlobalExceptionHandler.java`（仅此一个文件）

**改了什么**：
1. 新增 import：`org.springframework.web.context.request.async.AsyncRequestNotUsableException`（spring-web 自带，无需加依赖）。
2. 在兜底的 `@ExceptionHandler(Exception.class)` 之前，新增一个专门的处理方法：

```java
@ExceptionHandler(AsyncRequestNotUsableException.class)
public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
    log.debug("SSE client disconnected: {}", ex.getMessage());
}
```

**为什么这样修**：
- Spring 的 `@ExceptionHandler` 按异常类型**最具体匹配优先**，`AsyncRequestNotUsableException` 比 `Exception` 更具体，所以 SSE 断开会命中这个新方法，而不会落到 `handleUnexpected`。
- 方法返回 `void`：Spring 对 void 的 `@ExceptionHandler` 认为响应已由 handler 自行处理，**不会再写任何 body**。因此不会再去为已经是 `text/event-stream` 的响应找 JSON 转换器，从源头消除了二次抛出的 `HttpMessageNotWritableException`。
- 只 `log.debug` 一行：客户端已断开，没有可写的响应，这是正常事件而非错误，降级为 debug 后整屏 ERROR 栈 + WARN 失败都消失，只剩一行 debug。

**未动（按要求）**：Tomcat socket / SO_LINGER 配置、`ChatService.send(...)`、兜底 `handleUnexpected` 及其余所有处理方法均保持不变，正常异常路径（校验/not_found/conflict/internal_error 等）行为不受影响。

**验证**：`mvn -pl superagent-web -am -q -DskipTests compile` 编译通过。