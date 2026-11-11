package com.superagent.agent.chat;

/**
 * What an agent does with material the user hands it in a chat (a spec, minutes, an outline, data): kept once, as a
 * tidied document among the agent's files, so it outlives the session and later sessions and tasks work from it. It
 * is tidied, never rewritten, and kept as that one copy only.
 */
final class MaterialPrompt {

    private MaterialPrompt() {
    }

    static final String RULES = """

            ## 沉淀用户给的资料
            用户在对话里发来资料（需求、方案、会议纪要、产品或课程介绍、大纲、规范、数据、名单等以后还会用到的内容，不论长短），你要自己判断并把它存下来：
            - 用 file_write 整理成一份 Markdown 文档存进你的文件，文件名直接写内容是什么（如「XX 课程介绍.md」）。不要另存原文，一份资料只存这一份。
            - 整理只做格式：加标题层级、分段、列表、表格，修正明显的排版错乱。不改写、不删减、不总结、不加你自己的内容，原意和数字一字不改。
            - 同一份资料后来有补充或修改，先用 file_list 找到原文件，带 replace_id 覆盖，不另存。
            - 存好后在回复里顺带一句「已整理存为《文件名》」，不用问用户要不要存，也不用多解释。然后照常回应用户的问题。
            - 问题、指令、闲聊、一两句话的补充不算资料，不存。
            - 只是把资料整理好存起来，是在对话里直接做完的小事，不建任务；用户要你基于资料做别的事（写方案、做分析、出交付物）时，才按上面的规则判断要不要建任务。
            - 建任务要用到这份资料时，在任务说明里写上它的文件 id，不要只写「用户发的内容」。
            """;

    /** The same, next to each message: the system prompt alone loses to the wording. */
    static final String TURN_CHECK = """
            这条消息里有资料（以后还会用到的内容）就先用 file_write 只整理格式、不改原意地存成一份文档，回复里顺带一句存成了什么；这件事直接做，不建任务。
            """;
}
