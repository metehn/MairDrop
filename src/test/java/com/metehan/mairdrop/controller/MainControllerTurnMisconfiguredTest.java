package com.metehan.mairdrop.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;

@WebMvcTest(MainController.class)
@TestPropertySource(properties = {
        "webrtc.turn.url=turn:example.com:3478",
        "webrtc.turn.username=",
        "webrtc.turn.credential="
})
class MainControllerTurnMisconfiguredTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldFallBackToStunOnlyWhenTurnCredentialsMissing() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(model().attribute("iceServersJson",
                        "[{\"urls\":\"stun:stun.l.google.com:19302\"}]"));
    }
}
