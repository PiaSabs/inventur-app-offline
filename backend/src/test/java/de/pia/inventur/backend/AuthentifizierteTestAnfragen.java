package de.pia.inventur.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.net.http.HttpHeaders;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import de.pia.inventur.backend.security.InventurPrincipal;
import java.util.UUID;

/** Historischer Name: isolierte Controller-Vertragstests ohne HTTP-Sicherheitskette, nur Testcode. */
final class AuthentifizierteTestAnfragen {
    private AuthentifizierteTestAnfragen() {}

    static Antwort lese(MockMvc mvc, String pfad) throws Exception {
        var context = mvc.getDispatcherServlet().getWebApplicationContext();
        var controller = context.getBeansWithAnnotation(RestController.class).values().toArray();
        var converter = context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();
        var fachMvc = standaloneSetup(controller)
                .setControllerAdvice(context.getBeansWithAnnotation(org.springframework.web.bind.annotation.ControllerAdvice.class).values().toArray())
                .setMessageConverters(converter.toArray(HttpMessageConverter[]::new)).build();
        var vorher = SecurityContextHolder.getContext();
        var testKontext = SecurityContextHolder.createEmptyContext();
        testKontext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new InventurPrincipal(UUID.fromString("50000000-0000-4000-8000-000000000001")), null, List.of()));
        org.springframework.mock.web.MockHttpServletResponse response;
        try {
            SecurityContextHolder.setContext(testKontext);
            response = fachMvc.perform(get(pfad)).andReturn().getResponse();
        } finally { SecurityContextHolder.setContext(vorher); }
        var headers = new LinkedHashMap<String, List<String>>();
        response.getHeaderNames().forEach(name -> headers.put(name, List.copyOf(response.getHeaders(name))));
        return new Antwort(response.getStatus(), response.getContentAsString(), HttpHeaders.of(headers, (name, value) -> true));
    }

    record Antwort(int statusCode, String body, HttpHeaders headers) {}
}
