package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.entity.Vacancy;
import org.example.repository.VacancyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class VacancyParserService {

    private static final Logger log = LoggerFactory.getLogger(VacancyParserService.class);

    private final WebClient trudvsemWebClient;
    private final VacancyRepository repository;

    // Очередь хранит готовые объекты Vacancy
    private final BlockingQueue<Vacancy> vacancyQueue = new LinkedBlockingQueue<>(1000);

    // Защита от повторной обработки
    private final ConcurrentHashMap<String, Boolean> processedIds = new ConcurrentHashMap<>();

    // Кастомный пул потоков
    private final ExecutorService executorService = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    // Примитив синхронизации для файла
    private final ReentrantLock fileLock = new ReentrantLock();

    public VacancyParserService(WebClient trudvsemWebClient, VacancyRepository repository) {
        this.trudvsemWebClient = trudvsemWebClient;
        this.repository = repository;
        startDaemonLogger();
    }

    // Демон-поток для периодического логирования
    private void startDaemonLogger() {
        Thread daemon = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10_000);
                    log.info("[DAEMON] В очереди: {}, сохранено: {}",
                            vacancyQueue.size(), processedIds.size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        daemon.setDaemon(true);
        daemon.start();
    }

    // Простой Thread/Runnable
    public void parseInSimpleThread(String vacancyId) {
        Runnable task = () -> log.info("[SIMPLE THREAD] Запуск для ID: {}", vacancyId);
        new Thread(task).start();
    }

    // Планировщик: раз в 15 секунд разбирает очередь
    @Scheduled(fixedRate = 15_000)
    public void scheduledDrainQueue() {
        if (vacancyQueue.isEmpty()) return;
        log.info("[SCHEDULED] Забираем из очереди, размер: {}", vacancyQueue.size());
        while (!vacancyQueue.isEmpty()) {
            Vacancy v = vacancyQueue.poll();
            if (v != null) {
                executorService.submit(() -> saveVacancy(v));
            }
        }
    }

    private void saveVacancy(Vacancy v) {
        try {
            repository.save(v);
            saveToFile(v);
            log.info("[SAVED] {} | {}", v.getTitle(), v.getCity());
        } catch (Exception e) {
            log.error("[SAVE] Ошибка сохранения", e);
        }
    }

    // Запись в файл с ReentrantLock
    private void saveToFile(Vacancy vacancy) {
        fileLock.lock();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter("vacancies.txt", true))) {
            bw.write(vacancy.toString());
            bw.newLine();
        } catch (IOException e) {
            log.error("Ошибка записи в файл", e);
        } finally {
            fileLock.unlock();
        }
    }

    // Основной метод: загрузка с API Trudvsem
    public void fetchAndQueueVacancies(int page, int size) {
        log.info("[FETCH] СТАРТ page={}, size={}", page, size);
        try {
            int offset = page * size;
            String rawJson = trudvsemWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/vacancies")
                            .queryParam("limit", size)
                            .queryParam("offset", offset)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .onErrorReturn("")
                    .block();

            if (rawJson == null || rawJson.isBlank()) {
                log.warn("[FETCH] Пустой ответ от API");
                return;
            }

            log.info("[FETCH] Сырой ответ (первые 300): {}",
                    rawJson.substring(0, Math.min(300, rawJson.length())));

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(rawJson);

            // ===== ПРАВКА: results лежит на верхнем уровне, без обёртки data =====
            JsonNode resultsNode = root.get("results");
            if (resultsNode == null || resultsNode.isNull()) {
                log.warn("[FETCH] Нет поля 'results'. Ключи: {}",
                        mapper.convertValue(root, Map.class).keySet());
                return;
            }

            JsonNode vacanciesNode = resultsNode.get("vacancies");
            if (vacanciesNode == null || !vacanciesNode.isArray() || vacanciesNode.isEmpty()) {
                log.info("[FETCH] Массив vacancies пуст или отсутствует");
                return;
            }

            int queued = 0;
            for (JsonNode wrapper : vacanciesNode) {
                JsonNode v = wrapper.get("vacancy");
                if (v == null) continue;

                String id = v.path("id").asText("");
                if (id.isEmpty() || processedIds.putIfAbsent(id, true) != null) continue;

                Vacancy vacancy = new Vacancy();
                vacancy.setExternalId(id);
                vacancy.setTitle(v.path("job-name").asText(""));
                vacancy.setCompany(v.path("company").path("name").asText(""));

                JsonNode salaryNode = v.get("salary");
                if (salaryNode != null && salaryNode.isObject()) {
                    String from = salaryNode.path("from").asText("");
                    String to = salaryNode.path("to").asText("");
                    String currency = salaryNode.path("currency").asText("RUR");
                    if (from.isEmpty() && to.isEmpty()) {
                        vacancy.setSalary("не указана");
                    } else if (!from.isEmpty() && !to.isEmpty()) {
                        vacancy.setSalary(from + " – " + to + " " + currency);
                    } else {
                        vacancy.setSalary((from.isEmpty() ? "до " + to : "от " + from) + " " + currency);
                    }
                } else if (salaryNode != null && salaryNode.isTextual()) {
                    vacancy.setSalary(salaryNode.asText());
                } else {
                    vacancy.setSalary("не указана");
                }

                vacancy.setCity(v.path("region").path("name").asText(""));
                vacancy.setDatePosted(v.path("date-published").asText(""));
                vacancy.setRequirements(v.path("requirements").asText(""));

                vacancyQueue.add(vacancy);
                queued++;
            }
            log.info("[FETCH] Страница {}: в очередь добавлено {}", page, queued);

        } catch (Exception e) {
            log.error("[FETCH] Ошибка при получении списка", e);
        }
    }

    // Заглушка для совместимости
    public void addToQueue(String vacancyId) {
        log.warn("[QUEUE] Используйте /api/fetch");
    }

    public void parseOneVacancy(String companyCode, String vacancyId) {
        log.info("[PARSE ONE] companyCode={}, vacancyId={}", companyCode, vacancyId);
        try {
            String rawJson = trudvsemWebClient.get()
                    .uri("/v1/vacancies/vacancy/{companyCode}/{vacancyId}",
                            companyCode, vacancyId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .onErrorReturn("")
                    .block();

            if (rawJson == null || rawJson.isBlank()) {
                log.warn("[PARSE ONE] Пустой ответ от API");
                return;
            }

            log.info("[PARSE ONE] Сырой ответ (первые 300): {}",
                    rawJson.substring(0, Math.min(300, rawJson.length())));

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(rawJson);

            JsonNode resultsNode = root.get("results");
            if (resultsNode == null || resultsNode.isNull()) {
                log.warn("[PARSE ONE] Нет 'results'. Ключи: {}",
                        mapper.convertValue(root, Map.class).keySet());
                return;
            }

            JsonNode vacanciesNode = resultsNode.get("vacancies");
            if (vacanciesNode == null || !vacanciesNode.isArray() || vacanciesNode.isEmpty()) {
                log.warn("[PARSE ONE] Нет массива 'results.vacancies'");
                return;
            }

            JsonNode wrapper = vacanciesNode.get(0);
            JsonNode v = wrapper.get("vacancy");
            if (v == null) {
                log.warn("[PARSE ONE] Внутри vacancies[0] нет поля 'vacancy'");
                return;
            }

            Vacancy vacancy = new Vacancy();
            vacancy.setExternalId(vacancyId);
            vacancy.setTitle(v.path("job-name").asText(""));
            vacancy.setCompany(v.path("company").path("name").asText(""));
            vacancy.setCity(v.path("region").path("name").asText(""));
            vacancy.setDatePosted(v.path("date-published").asText(""));
            vacancy.setRequirements(v.path("requirements").asText(""));

            // Зарплата
            JsonNode salaryNode = v.get("salary");
            if (salaryNode != null && salaryNode.isObject()) {
                String from = salaryNode.path("from").asText("");
                String to = salaryNode.path("to").asText("");
                String currency = salaryNode.path("currency").asText("RUR");
                if (from.isEmpty() && to.isEmpty()) {
                    vacancy.setSalary("не указана");
                } else if (!from.isEmpty() && !to.isEmpty()) {
                    vacancy.setSalary(from + " – " + to + " " + currency);
                } else {
                    vacancy.setSalary((from.isEmpty() ? "до " + to : "от " + from) + " " + currency);
                }
            } else if (salaryNode != null && salaryNode.isTextual()) {
                vacancy.setSalary(salaryNode.asText());
            } else {
                vacancy.setSalary("не указана");
            }

            // Защита от дубликатов
            if (processedIds.putIfAbsent(vacancyId, true) != null) {
                log.info("[PARSE ONE] Вакансия {} уже обработана", vacancyId);
                return;
            }

            vacancyQueue.add(vacancy);
            log.info("[PARSE ONE] В очередь добавлено: {}", vacancy.getTitle());

        } catch (Exception e) {
            log.error("[PARSE ONE] Ошибка при запросе", e);
        }
    }
}