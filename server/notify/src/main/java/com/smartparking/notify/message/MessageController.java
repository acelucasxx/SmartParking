package com.smartparking.notify.message;

import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.AppPrincipal;
import com.smartparking.common.web.PageResult;
import com.smartparking.iot.DbTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 站内消息（契约 message 分组）：只能看到、标记本人的消息 */
@RestController
@RequestMapping("/api/app/messages")
public class MessageController {

    private final MessageStore store;

    public MessageController(MessageStore store) {
        this.store = store;
    }

    public record Message(
            long id,
            MessageType type,
            Long deviceId,
            String title,
            String content,
            boolean read,
            OffsetDateTime createdAt) {}

    public record ReadRequest(List<Long> ids, Boolean all) {}

    @GetMapping
    public ApiResponse<PageResult<Message>> list(
            AppPrincipal user,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int[] ps = PageResult.normalize(page, size);
        PageResult<MessageStore.Message> result = store.list(user.userId(), ps[0], ps[1]);
        return ApiResponse.ok(new PageResult<>(
                result.total(),
                result.page(),
                result.size(),
                result.items().stream()
                        .map(m -> new Message(
                                m.id(),
                                m.type(),
                                m.deviceId(),
                                m.title(),
                                m.content(),
                                m.read(),
                                m.createdAt().atZone(DbTime.ZONE).toOffsetDateTime()))
                        .toList()));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Integer>> unread(AppPrincipal user) {
        return ApiResponse.ok(Map.of("count", store.unread(user.userId())));
    }

    @PostMapping("/read")
    public ApiResponse<Void> read(AppPrincipal user, @RequestBody ReadRequest body) {
        store.markRead(user.userId(), body.ids(), Boolean.TRUE.equals(body.all()));
        return ApiResponse.ok();
    }
}
