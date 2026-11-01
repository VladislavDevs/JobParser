package org.example.controller;

import org.example.entity.Vacancy;
import org.example.repository.VacancyRepository;
import org.example.service.VacancyParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VacancyController {

    private final VacancyParserService parserService;
    private final VacancyRepository repository;

    @PostMapping("/parse")
    public ResponseEntity<String> parse(@RequestBody List<String> vacancyIds) {
        vacancyIds.forEach(parserService::addToQueue);
        return ResponseEntity.ok("Добавлено в очередь: " + vacancyIds.size());
    }

    @GetMapping("/answer")
    public ResponseEntity<List<Vacancy>> getAnswer(
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "title") String sortBy) {

        List<Vacancy> all = repository.findAll();

        List<Vacancy> result = all.parallelStream()
                .filter(v -> city == null
                        || (v.getCity() != null && v.getCity().contains(city)))
                .sorted((v1, v2) -> {
                    if ("salary".equalsIgnoreCase(sortBy)) {
                        return v1.getSalary().compareTo(v2.getSalary());
                    }
                    if ("date".equalsIgnoreCase(sortBy)) {
                        return v1.getDatePosted().compareTo(v2.getDatePosted());
                    }
                    return v1.getTitle().compareTo(v2.getTitle());
                })
                .skip((long) page * size)
                .limit(size)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
