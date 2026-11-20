# 字幕任务（subtitle）

第一版只做原话转写：中英混说原样保留，不翻译。详情页样式稿见 [subtitle-task-detail-mock.html](subtitle-task-detail-mock.html)。

## 流程（`SubtitleHarness`，7 步）

1. **素材**：从任务说明里的文件 id 取音频（一个任务一段）和参考资料（pptx / pdf / txt / md / srt / vtt，按页提取文字；字幕文件去掉序号、时间码和 WEBVTT 头后当纯文本）。
2. **术语**：资料里的英文术语（缩写、大小写混写、带数字）+ 最便宜的文字模型提取的专名、人名；新词加进项目术语表（不在项目里就加进公司术语表），再合并公司和项目两级。
3. **听写**：所有可用的听写模型并行，都带术语提示；由平台直接调用，不开大模型会话。网络抖动这类偶发错误在这一步内部最多重试 2 次；最终失败也不换文字模型（听写不用文字模型）。
4. **对齐投票**：以带时间轴的那份作骨架，中文按字、英文按词对齐（不分大小写、不看标点）。各份一致的直接采用，不一致的地方切成分歧段。
5. **校准**：每个校准模型拿到「各份原文、前后文、对应资料页、术语」，按 JSON 给出每个分歧段的写法。
   - 两个及以上模型：超过全部校准模型数的一半给出同一写法才采用（没回答的算不同意）。
   - 只有一个模型：它的写法必须是某份听写原文才采用。
   - 没有文字模型：按听写多数决定。
   - 其余列为疑点，字幕里暂用骨架的写法。模型只能改分歧段，回答里的其他编号一律丢弃。
6. **检查**（平台）：
   - 时间轴合法、不重叠；
   - 首条不晚于语音开始，末条结束不早于音频时长减 2 秒；
   - 没有空字幕条；
   - 每行不超过 20 字（英文字母算半个）、每条最多 2 行、单条不超过 7 秒；
   - 中英文之间有空格；
   - 术语大小写和术语表一致。

   平台先自动修正（切分、换行、加空格、统一大小写）。仍不通过就把太长的条目交给最强的校准模型重新断句，文字有变动的结果不采用。最多 2 轮，之后停下等用户。
7. **交付**：srt 和疑点清单存进「文件」（标签「字幕」）。疑点清单逐条列出时间码、各模型原文、校准结果、最后采用的写法和理由。

用户在详情页「疑点」里改了某处，平台会重写「文件」里的 srt 和疑点清单（覆盖原文件），并把改动写回术语表：改后的写法记为术语，原来的写法记为「常被误识别为」。

## 兜底

- **只有 1 个听写模型**：每一条都当作分歧段交给校准，规则同上。
- **没有带时间轴的听写模型**：建任务时直接报错，提示管理员去配置。
- **确定性错误**（重试多少次都不会好）：音频超过 24 MB 且服务器没有 ffmpeg、格式不支持、ffmpeg 读不了、没有听写模型或 Key。media 抛 core 的 `NeedsUserException`（`IllegalArgumentException` 的子类），harness 把任务停在「等你确认」，写清原因和办法（装 ffmpeg；或先转成单声道 mp3 压到 24 MB 以内；或去配 Key）。不升档、不进看门狗重试；同一原因再停下时不重复写运行日志、不重复通知（`TaskStatusChanged.repeated`）。用户回复「继续」后从停下的那一步接着做；回复里附上新的音频文件 id 就改用它。普通任务 harness 也认这个异常。
- **ffmpeg**：平台依赖。「模型」页显示是否安装和版本，可一键安装（macOS 用 brew，Linux 用 apt-get / dnf / yum / apk，需要 root 或免密 sudo）；Docker 镜像已带。转写每次调用时现查，装好不用重启。`subtitle_models` 和字幕确认表会写明服务器有没有 ffmpeg，以及没有时的大小和格式限制。

## 模型能力（cost 的 `ModelCapabilityService`，接口在 core 的 `ModelCapabilities`）

- **听写**：做模型检查时顺带探测。OpenAI Key 下给每个转写模型（`whisper-1`、`gpt-4o-transcribe`、`gpt-4o-mini-transcribe`）发 1 秒静音，返回 200 即可用。结果按公司存在系统设置里（键 `speech.models`）。以后接新引擎，实现一个 `SpeechEngine` 就会自动纳入；不需要 Key 的本地程序（`provider()` 返回 null）每次检查都会探测。
- **文字**：推理检查已通过、并且开了「用于任务」的模型。
- **校准模型**：每家厂商取最强（价格最高）的一个，按强弱排序，最多 N 个。N 默认 3，在系统设置里按公司调（1～10）。任务固定模型开关打开时，只从固定模型里挑。

## 分层

| 模块 | 内容 |
|---|---|
| core | `media/*` 接口（`SpeechEngine`、`SpeechToText`、`TranscriptAligner`、`ModelCapabilities`、`TextTokens`），`glossary/*` |
| platform | `SpeechProbes`（随模型检查探测）、`task.subtitle.max-calibrators` 设置、`FfmpegService`（ffmpeg 状态与安装） |
| media | `OpenAiSpeechEngine`、`SpeechDispatcher`、`SubtitleAligner` |
| cost | `ModelCapabilityService` |
| files | `DocumentText`（pptx / pdf / 文本按页取文字） |
| glossary（新，L2） | `GlossaryService`：两级术语表、从资料新增、疑点修改写回 |
| storage | `glossary_term` 表（带 company_id） |
| task | `harness/subtitle/*`、`subtitle_models` 工具、`kind=subtitle` |
| web | `GET /tasks/{id}/subtitle`、`PUT /tasks/{id}/subtitle/disputes/{spanId}`、`/glossary` 的增删改查 |
| ui | `task-subtitle.js`：字幕 / 对比 / 疑点 / 术语四个标签页 |

## 已知限制

- 4o 系列按 OpenAI 文档用 `json` 返回格式（不带时间轴），每段 240 秒切分（需要 ffmpeg）；这一点还没有用真实 Key 实测。
- 骨架的时间轴是 whisper 的分段时间，切分时按字数比例估算时间。
- 术语表界面目前只在任务详情的「术语」页里展示；手动增删改走 `/api/v1/glossary`，还没有单独的管理页。
