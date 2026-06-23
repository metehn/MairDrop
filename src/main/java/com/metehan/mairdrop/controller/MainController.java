package com.metehan.mairdrop.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class MainController {

    private static final String DEFAULT_STUN_URL = "stun:stun.l.google.com:19302";
    private static final String DEFAULT_ICE_SERVERS_JSON =
            "[{\"urls\":\"" + DEFAULT_STUN_URL + "\"}]";

    private final ObjectMapper objectMapper;
    private final String turnUrl;
    private final String turnUsername;
    private final String turnCredential;

    public MainController(ObjectMapper objectMapper,
                          @Value("${webrtc.turn.url:}") String turnUrl,
                          @Value("${webrtc.turn.username:}") String turnUsername,
                          @Value("${webrtc.turn.credential:}") String turnCredential) {
        this.objectMapper = objectMapper;
        this.turnUrl = turnUrl;
        this.turnUsername = turnUsername;
        this.turnCredential = turnCredential;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("iceServersJson", buildIceServersJson());
        return "index";
    }

    private String buildIceServersJson() {
        List<Map<String, String>> iceServers = new ArrayList<>();

        Map<String, String> stun = new LinkedHashMap<>();
        stun.put("urls", DEFAULT_STUN_URL);
        iceServers.add(stun);

        if (!turnUrl.isBlank()) {
            Map<String, String> turn = new LinkedHashMap<>();
            turn.put("urls", turnUrl);
            turn.put("username", turnUsername);
            turn.put("credential", turnCredential);
            iceServers.add(turn);
        }

        try {
            return objectMapper.writeValueAsString(iceServers);
        } catch (JsonProcessingException e) {
            return DEFAULT_ICE_SERVERS_JSON;
        }
    }
}
