package com.superagent.api.notice;

/**
 * The to-do page's numbers: what is open now (to confirm, to do, unread notices) and how much work
 * finished for the user — today, this week (from Monday), this month and ever.
 */
public record NoticeStatsDto(int toConfirm, int toDo, int unreadNotices, long doneToday, long doneThisWeek,
                             long doneThisMonth, long doneTotal) {
}
