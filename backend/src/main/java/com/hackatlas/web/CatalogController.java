package com.hackatlas.web;

import com.hackatlas.catalog.CatalogDtos;
import com.hackatlas.catalog.CatalogService;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/hackathons")
    public CatalogDtos.HackathonPage list(@RequestParam MultiValueMap<String, String> parameters) {
        return catalog.list(CatalogQueryParser.parse(parameters));
    }

    @GetMapping("/hackathons/{id}")
    public CatalogDtos.HackathonDetail detail(@PathVariable String id) {
        return catalog.detail(CatalogQueryParser.id(id)).orElseThrow(CatalogNotFoundException::new);
    }

    @GetMapping("/metadata/filters")
    public CatalogDtos.FilterMetadata filters() {
        return catalog.metadata();
    }
}
