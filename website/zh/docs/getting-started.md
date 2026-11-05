---
title: 快速开始
description: 构建、配置、运行 SuperAgent,并把第一项工作跑通。
---

# 快速开始

SuperAgent 是基于 **Java 21** / Spring Boot 的 Maven 多模块工程。`superagent-server` 是唯一可执行的 Spring Boot jar。

## 环境要求

- 构建需 **Java 21+** 和 **Maven**;运行需服务器上有 **Java 21+**。
- **MySQL 8** —— 默认存储;首次启动时自动建表。
- 服务器 `PATH` 上的 Agent CLI:[Claude Code](https://www.npmjs.com/package/@anthropic-ai/claude-code) 和/或 [Codex](https://www.npmjs.com/package/@openai/codex)。也可以稍后在 **Models** 页面安装。

## 构建发布包

在仓库根目录:

```bash
mvn clean package -PreleaseTgz          # 加 -DskipTests 可跳过测试
```

这会产出 `superagent-server/target/superagent-<version>.tar.gz`,解包后结构如下:

```text
superagent-<version>/
├── bin/      superagent-start.sh、superagent-stop.sh
├── config/   application.properties、superagent.env
├── libs/     superagent.jar
├── logs/     superagent.log、console.out、superagent.pid
└── data/     agent 与 task 的工作区
```

## 配置

首次启动前编辑 `config/superagent.env`(文件权限保持 `600`):

```bash
SUPERAGENT_ADMIN_PASSWORD=<一个强密码>
SUPERAGENT_STORAGE_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/superagent
SUPERAGENT_STORAGE_MYSQL_USERNAME=superagent
SUPERAGENT_STORAGE_MYSQL_PASSWORD=<密码>
```

`config/application.properties` 用于覆盖打进 jar 的默认配置;改动重启即生效,无需重新打包。

## 启动与停止

```bash
tar -xzf superagent-<version>.tar.gz
cd superagent-<version>

bin/superagent-start.sh          # 后台启动
bin/superagent-start.sh force    # 重启:先停掉运行中的进程,再启动
bin/superagent-stop.sh           # 停止
```

启动脚本会检查 Java 21+,并在 `/actuator/health` 响应后才报告成功。启动后,打开 `http://<host>:8080` 并以管理员身份登录。

## 把第一项工作跑通

1. 以管理员身份**登录**。
2. 在 **Models** 页面接入一个模型供应商(需要的话顺便安装编码 CLI)。
3. 创建第一个 **Agent**,或打开一个 **Task**。
4. 通过收件箱把工作交给你的 **SuperAgent**,让它计划、派活并回报。

## 本地开发

```bash
mvn clean install                           # 构建并测试所有模块
mvn -pl superagent-server spring-boot:run   # 在 :8080 上运行全部
```

冒烟检查 web 装配:`curl localhost:8080/api/v1/meta`。
