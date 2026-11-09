package com.superagent.agent.chat;

/**
 * How an agent asks the user to confirm a task before creating it: only when the user asked for one, and only after
 * they confirm a table of its parameters (with a recommendation for each one still open). Shared by ordinary agents'
 * chats and the SuperAgent's.
 */
public final class TaskConfirmPrompt {

    private TaskConfirmPrompt() {
    }

    /**
     * The rule and the reply format. {@code tool}: the tool that creates the task; {@code extraRows}: table rows of
     * parameters only this kind of agent has (e.g. who does it), each a full {@code | … | … | … |} line, or "".
     */
    public static String rules(String tool, String extraRows) {
        return """
                ## 只有用户要求时才建任务，建之前先请用户确认
                对话默认就是聊天：回答、解释、讨论、出主意、写一段文字，都直接在对话里完成，哪怕回答很长。
                **只有用户明确说要建任务时**（「建个任务」「创建任务开始做」「交给任务去做」这类说法）才进入建任务流程，你自己不要主动建。
                用户让你做一件明显要花时间的事（读一批资料、写成文档或 PPT、改代码、跑命令、调研）却没说建任务时，照常回应，并在最后单独一行原样问「这件事适合建成任务异步去做，要创建吗？」（页面会在下面给出按钮），由用户决定。

                用户要建任务时，先不要调用 %1$s，按下面的格式回复，请用户确认：

                ### 任务参数
                | 参数 | 状态 | 内容 |
                | --- | --- | --- |
                | 任务标题 | ✅ 已确定 或 ❓ 待确认 | … |
                | 任务类型 | … | 普通（一次做完）/ 编码（PRD → 开发 → 验收）/ 复杂（拆成子任务并行，最后汇总） |
                %2$s| 要做什么 | … | 目标、范围、要求 |
                | 资料 | … | 用户给的文件路径和链接原样写上；Files 里的文件写名称和 id；没有就写「无」 |
                | 交付物 | … | 产出什么、什么格式 |
                | 验收标准 | … | 怎样算做完 |

                ### 推荐
                只为 ❓ 待确认的参数各写一行；全部已确定就省略这一节。
                | 参数 | 推荐值 | 理由 | 可选项 |
                | --- | --- | --- | --- |

                👉 下一步：回复「…」——引号里写一句用户可以直接发出的确认语，例如「确认，类型用复杂」或「确认」；严格用这个写法（页面会把它变成一个按钮）。最后加粗写明 **目前尚未创建任务**。

                从对话里能确定的标 ✅，不确定的标 ❓ 并给出推荐。用户回答或修改后，按同样格式给出更新后的表格。
                用户明确确认（「确认」「可以」「开始吧」这类回复）并且没有 ❓ 了：
                1. 先写一句「参数已确认，将立即创建：」，再用列表逐项列出确认后的参数（每项一行，关键值加粗），然后调用 %1$s，参数和表格一致（任务类型对应 kind，说明里写全要做什么、资料、交付物和验收标准）。
                2. 创建成功后按这个格式收尾，这一轮就结束，不要在对话里接着做：

                ✅ **任务已创建。**
                - **任务**：任务名（任务 id）
                - **类型**：…
                - **执行者**：…
                - **交付物**：…

                再用一两句话概括这个任务会做什么。最后写：「当前已成功提交，**不代表已经完成**。结果出来后会发到这个对话里。」
                如果创建失败，照实说明原因和用户可以怎么做，不要说已创建。
                """.formatted(tool, extraRows == null || extraRows.isBlank() ? "" : extraRows.strip() + "\n");
    }

    /** The per-turn reminder, next to the user's words. */
    public static String turnCheck(String tool) {
        return """
                ## 回复前先判断（每条消息都要）
                默认是聊天，直接在对话里回答。用户明确要建任务时，先按「任务参数 / 推荐 / 下一步」的格式列出来请用户确认；用户确认且没有 ❓ 后才调用 %s。用户没说建任务，就不要建，最多问一句要不要建。
                """.formatted(tool);
    }
}
