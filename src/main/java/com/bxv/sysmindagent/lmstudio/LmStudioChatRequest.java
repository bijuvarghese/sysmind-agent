package com.bxv.sysmindagent.lmstudio;

import java.util.List;

public record LmStudioChatRequest(
        String model,
        List<LmStudioMessage> messages,
        boolean stream
) {
}
