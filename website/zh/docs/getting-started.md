---
title: 快速开始
description: 用 Docker 或发布包为公司部署 SuperAgent,完成设置,把第一项工作跑通。
---

# 快速开始

SuperAgent 是自托管的:部署在你们自己的服务器上,配合 MySQL 使用。每个[版本](https://github.com/oryx-labs/SuperAgent/releases)都提供 Docker 镜像和发布包。

## 方式一:Docker(推荐)

镜像里预装了 Java 21、Node.js 24 和编码 Agent CLI(Claude Code、Codex、Gemini CLI、Qwen Code、Kimi Code、DeepSeek Harness)。

```bash
cp docker/.env.example docker/.env      # 设置管理员密码和 MySQL 密码
docker compose -f docker/docker-compose.yml --env-file docker/.env up -d
```

这会在 `http://<host>:18080` 启动 SuperAgent 和 MySQL。如果服务器不能直接访问模型 API,在 `docker/.env` 里设置 `HTTPS_PROXY`。

## 方式二:发布包

需要 **Java 21+** 和 **MySQL 8**(首次启动自动建表)。编码 CLI 可以之后在「模型」页安装。

```text
superagent-<version>/
├── bin/      superagent-start.sh、superagent-stop.sh
├── config/   application.yml、superagent.env
├── libs/     superagent.jar
├── logs/
└── data/     agent 与 task 的工作区
```

首次启动前在 `config/superagent.env` 里设置(文件权限保持 `600`):

```bash
SUPERAGENT_STORAGE_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/superagent
SUPERAGENT_STORAGE_MYSQL_USERNAME=superagent
SUPERAGENT_STORAGE_MYSQL_PASSWORD=<密码>
```

```bash
bin/superagent-start.sh          # 后台启动
bin/superagent-start.sh force    # 重启
bin/superagent-stop.sh           # 停止
```

启动脚本会检查 Java 21+,健康检查通过后才报告启动成功。

## 完成设置(管理员)

打开 `http://<host>:18080`,在登录页点「注册一个组织」,会得到这个组织的管理员账号(密码只显示一次,首次登录时改成你自己的)。每个组织各自独立,有自己的数据和模型 Key。其余设置都在页面上完成,不用改文件:

1. **模型**:安装需要的 CLI,添加公司的 API Key。每个 Key 都会用真实请求检查,失败时会说明原因。
2. **系统设置 → 基本设置**:公司名称、Logo、默认模型;如果大家通过代理或域名访问,填写对外地址。
3. **系统设置 → 连接配置**:按需配置公司的 GitHub / Gitee OAuth 应用和飞书应用。见[连接与消息](./connectors)。
4. **系统设置 → 文件存储**:文件存在服务器本地,或使用 S3 兼容存储。
5. **用户**:添加可以登录的人(用户名和密码,或手机号)。

## 第一项工作(每个人)

1. 打开 **Agent**,创建一个 Agent:配好性格、模型和 Skills,然后开始对话。
2. 或者把事情丢给你的 **SuperAgent**:它做计划、请你批准,然后组队去做。
3. 长任务交给**任务**;重复性的工作交给**自动化**。
4. 在**连接**页,授权你希望 Agent 以你的身份使用的系统(比如 GitHub)。
5. 看看你的**待办**:需要你处理的事,以及 Agent 替你做完的事。

## 开发者

```bash
mvn clean install                           # 构建并测试所有模块
mvn -pl superagent-server spring-boot:run   # 在 :18080 上运行全部
```

发版:提一个标题以 `release: …` 开头、并在 pom 里升好版本号的 Pull Request,合并后会自动发布 GitHub Release(发布包 + `.sha256`)和 Docker 镜像。
