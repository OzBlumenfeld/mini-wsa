package com.akamai.miniwsa.api.controller;

import com.akamai.miniwsa.api.dto.IngestionEventStatus;
import com.akamai.miniwsa.api.dto.IngestionResponse;
import com.akamai.miniwsa.service.IngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestionController.class)
class IngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IngestionService ingestionService;

    @Test
    void arrayOfOneEventReturns201WithResults() throws Exception {
        when(ingestionService.ingest(any())).thenReturn(new IngestionResponse(
                1, 0, List.of(IngestionResponse.EventResult.accepted("evt-1"))));

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[ { \"eventId\": \"evt-1\" } ]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.results[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.results[0].status").value("accepted"));
    }

    @Test
    void arrayBodyReturns201WithMixedResults() throws Exception {
        when(ingestionService.ingest(any())).thenReturn(new IngestionResponse(
                1, 1, List.of(
                        IngestionResponse.EventResult.accepted("evt-ok"),
                        IngestionResponse.EventResult.rejected("evt-bad", List.of("path: must not be blank"))
                )));

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[ { \"eventId\": \"evt-ok\" }, { \"eventId\": \"evt-bad\" } ]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.results[0].status").value("accepted"))
                .andExpect(jsonPath("$.results[1].status").value("rejected"))
                .andExpect(jsonPath("$.results[1].errors[0]").value("path: must not be blank"));
    }

    @Test
    void nonArrayBodyReturns400() throws Exception {
        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"just a string\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unparseableJsonReturns400() throws Exception {
        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not valid json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void emptyArrayReturns400() throws Exception {
        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void oversizedBatchReturns400() throws Exception {
        var array = objectMapper.createArrayNode();
        IntStream.rangeClosed(1, 501).forEach(i -> {
            ObjectNode stub = objectMapper.createObjectNode();
            stub.put("eventId", "evt-" + i);
            array.add(stub);
        });

        mockMvc.perform(post("/v1/events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(array)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
