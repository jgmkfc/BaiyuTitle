package net.baiyu.title.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 聊天输入完成导航策略测试。 */
class ChatInputRequestTest {

    @Test
    void regularRequestReturnsAfterCompletion() {
        final ChatInputRequest request = new ChatInputRequest(
                "prompt", InputValidators.length(1, 10), input -> {
                }, () -> {
                }, null);

        assertTrue(request.returnAfterComplete());
    }

    @Test
    void chainedRequestCanDeferCompletionReturn() {
        final ChatInputRequest request = new ChatInputRequest(
                "prompt", InputValidators.length(1, 10), input -> {
                }, () -> {
                }, null, false);

        assertFalse(request.returnAfterComplete());
    }
}
