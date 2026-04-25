package com.elastiflix.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AppProperties {

    @Value("${elasticsearch.index}")
    public String esIndex;

    @Value("${app.page-size}")
    public int pageSize;

    @Value("${app.tmdb-image-base}")
    public String tmdbImageBase;
}
