package com.elastiflix.controller.web;

import com.elastiflix.model.SearchMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(HomeController.class)
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rendersTheLandingPageWithEverySearchMode() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attribute("modes", SearchMode.values()));
    }

    @Test
    void preselectsTheDefaultModeFromTheEnumRatherThanTheTemplate() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(model().attribute("currentMode", SearchMode.defaultMode().name()));
    }

    @Test
    void explainsTheBounceBackFromAMovieThatIsNoLongerIndexed() throws Exception {
        // MovieDetailController redirects here with ?notFound=true. Before this the parameter
        // was rendered nowhere, so the user was returned to the home page with no explanation.
        mockMvc.perform(get("/").param("notFound", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not in the index")));
    }

    @Test
    void showsNoSuchNoticeOnAnOrdinaryVisit() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(content().string(not(containsString("not in the index"))));
    }
}
