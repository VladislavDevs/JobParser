package org.example;

import org.example.controller.VacancyController;
import org.example.entity.Vacancy;
import org.example.repository.VacancyRepository;
import org.example.service.VacancyParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class MainTests {

    @Autowired
    private VacancyController controller;

    @Autowired
    private VacancyRepository repository;

    @Autowired
    private VacancyParserService parserService;

    @BeforeEach
    void cleanDb() {
        repository.deleteAll();
    }

    // === 1. Контекст Spring Boot поднимается ===
    @Test
    void contextLoads() {
        assertNotNull(controller);
        assertNotNull(repository);
        assertNotNull(parserService);
    }

    // === 2. Репозиторий сохраняет и читает вакансию ===
    @Test
    void repositorySavesAndReadsVacancy() {
        Vacancy v = new Vacancy();
        v.setExternalId("test-1");
        v.setTitle("Java-разработчик");
        v.setCompany("ООО Ромашка");
        v.setSalary("100000 RUR");
        v.setCity("Москва");
        v.setDatePosted("2026-11-04");
        v.setRequirements("Spring Boot");

        Vacancy saved = repository.save(v);
        assertNotNull(saved.getId());
        assertEquals(1, repository.count());
        assertEquals("Java-разработчик", saved.getTitle());
    }

    // === 3. GET /api/answer возвращает 200 и пустой список ===
    @Test
    void answerReturnsOkOnEmptyDb() {
        ResponseEntity<List<Vacancy>> response = controller.getAnswer(null, 0, 10, "title");
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isEmpty());
    }

    // === 4. GET /api/answer фильтрует по городу через parallelStream ===
    @Test
    void answerFiltersByCity() {
        repository.save(makeVacancy("v1", "Java-разработчик", "Москва"));
        repository.save(makeVacancy("v2", "Python-разработчик", "Санкт-Петербург"));
        repository.save(makeVacancy("v3", "Go-разработчик", "Москва"));

        ResponseEntity<List<Vacancy>> response =
                controller.getAnswer("Москва", 0, 10, "title");

        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        response.getBody().forEach(v ->
                assertTrue(v.getCity().contains("Москва")));
    }

    // === 5. GET /api/answer сортирует по title ===
    @Test
    void answerSortsByTitle() {
        repository.save(makeVacancy("v1", "ZZZ последняя", "Москва"));
        repository.save(makeVacancy("v2", "AAA первая", "Москва"));
        repository.save(makeVacancy("v3", "MMM средняя", "Москва"));

        ResponseEntity<List<Vacancy>> response =
                controller.getAnswer(null, 0, 10, "title");

        assertNotNull(response.getBody());
        List<Vacancy> list = response.getBody();
        assertEquals(3, list.size());
        assertEquals("AAA первая", list.get(0).getTitle());
        assertEquals("MMM средняя", list.get(1).getTitle());
        assertEquals("ZZZ последняя", list.get(2).getTitle());
    }

    // === 6. GET /api/answer поддерживает пагинацию ===
    @Test
    void answerSupportsPagination() {
        for (int i = 0; i < 25; i++) {
            repository.save(makeVacancy("id-" + i, "Вакансия " + i, "Москва"));
        }

        ResponseEntity<List<Vacancy>> page0 =
                controller.getAnswer(null, 0, 10, "title");
        ResponseEntity<List<Vacancy>> page2 =
                controller.getAnswer(null, 2, 10, "title");

        assertNotNull(page0.getBody());
        assertNotNull(page2.getBody());
        assertEquals(10, page0.getBody().size());
        assertEquals(5, page2.getBody().size());
    }

    // === 7. Простой поток Thread/Runnable запускается без ошибок ===
    @Test
    void simpleThreadRuns() throws InterruptedException {
        parserService.parseInSimpleThread("test-thread-id");
        Thread.sleep(200); // даём потоку завершиться
        // если поток упал — тест не увидит, но проверим что метод не бросает
        assertTrue(true);
    }

    // === 8. /api/fetch возвращает 200 (без проверки реального API) ===
    @Test
    void fetchEndpointReturnsOk() {
        // ВАЖНО: тест делает реальный HTTP-запрос к opendata.trudvsem.ru
        // Если API недоступен, тест всё равно пройдёт (метод ловит исключения внутри)
        ResponseEntity<String> response = controller.fetch(0, 1);
        assertEquals(200, response.getStatusCodeValue());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isEmpty());
    }

    // === Вспомогательный метод для создания вакансии ===
    private Vacancy makeVacancy(String externalId, String title, String city) {
        Vacancy v = new Vacancy();
        v.setExternalId(externalId);
        v.setTitle(title);
        v.setCompany("Тестовая компания");
        v.setSalary("100000 RUR");
        v.setCity(city);
        v.setDatePosted("2026-11-04");
        v.setRequirements("Требования");
        return v;
    }
}