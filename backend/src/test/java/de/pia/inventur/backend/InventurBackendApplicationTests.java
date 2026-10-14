package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import de.pia.inventur.backend.controller.HealthController;
import de.pia.inventur.backend.controller.ZaehlerlistenController;
import de.pia.inventur.backend.dto.ZaehlerlisteResponse;
import de.pia.inventur.backend.service.ZaehlerlistenService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import de.pia.inventur.backend.security.SecurityKonfiguration;
import de.pia.inventur.backend.security.OidcBenutzerZuordnung;

import tools.jackson.databind.json.JsonMapper;

@WebMvcTest({HealthController.class, ZaehlerlistenController.class})
@Import(SecurityKonfiguration.class)
class InventurBackendApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ZaehlerlistenService zaehlerlistenService;

    @MockitoBean
    private OidcBenutzerZuordnung oidcBenutzerZuordnung;

    @Test
    void healthReturnsHttp200AndUpStatus() throws Exception {
        var response = mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("application/json", response.getContentType());
        assertEquals("{\"status\":\"UP\"}", response.getContentAsString());
    }

    @Test
    void zaehlerlistenReturnsHttp200AndExactlyTwoDemoEntries() throws Exception {
        when(zaehlerlistenService.findeAlle()).thenReturn(List.of(
                new ZaehlerlisteResponse(UUID.fromString("10000000-0000-4000-8000-000000000001"),
                        "DEMO-01", "Demo-Zaehlerliste Sonnenlager"),
                new ZaehlerlisteResponse(UUID.fromString("10000000-0000-4000-8000-000000000002"),
                        "DEMO-02", "Demo-Zaehlerliste Mondlager")));
        var response = AuthentifizierteTestAnfragen.lese(mockMvc, "/api/v1/zaehlerlisten");
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());

        JsonMapper mapper = JsonMapper.builder().build();
        assertEquals(mapper.readTree("""
                [
                  {
                    "id": "10000000-0000-4000-8000-000000000001",
                    "code": "DEMO-01",
                    "bezeichnung": "Demo-Zaehlerliste Sonnenlager"
                  },
                  {
                    "id": "10000000-0000-4000-8000-000000000002",
                    "code": "DEMO-02",
                    "bezeichnung": "Demo-Zaehlerliste Mondlager"
                  }
                ]
                """), mapper.readTree(response.body()));
    }

}
