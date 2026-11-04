package org.example.controller;

import org.example.entity.Vacancy;
import org.example.repository.VacancyRepository;
import org.example.service.VacancyParserService;
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
public class VacancyController {

    private final VacancyParserService parserService;
    private final VacancyRepository repository;

    public VacancyController(VacancyParserService parserService, VacancyRepository repository) {
        this.parserService = parserService;
        this.repository = repository;
    }

    // Ручное добавление ID в очередь (если известны конкретные ID)
    @PostMapping("/parse")
    public ResponseEntity<String> parse(@RequestBody List<String> vacancyIds) {
        vacancyIds.forEach(parserService::addToQueue);
        return ResponseEntity.ok("Добавлено в очередь: " + vacancyIds.size());
    }

    // Автоматический сбор вакансий с портала «Работа России»
    @PostMapping("/fetch")
    public ResponseEntity<String> fetch(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        parserService.fetchAndQueueVacancies(page, size);
        return ResponseEntity.ok("Запущен сбор вакансий: страница " + page + ", размер " + size);
    }

    // Получение результатов с фильтрацией, сортировкой и пагинацией через parallelStream
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
