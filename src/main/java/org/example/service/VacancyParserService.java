package org.example.service;

import org.example.entity.Vacancy;
import org.example.repository.VacancyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class VacancyParserService {

    private final WebClient hhWebClient;
    private final VacancyRepository repository;

    private final BlockingQueue<String> vacancyIdQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, Boolean> processedIds = new ConcurrentHashMap<>();

    private final ExecutorService executorService = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final ReentrantLock fileLock = new ReentrantLock();

    public VacancyParserService(WebClient hhWebClient, VacancyRepository repository) {
        this.hhWebClient = hhWebClient;
        this.repository = repository;
        startDaemonLogger();
    }

    private void startDaemonLogger() {
        Thread daemon = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10_000);
                    log.info("[DAEMON] В очереди: {}, обработано: {}",
                            vacancyIdQueue.size(), processedIds.size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        daemon.setDaemon(true);
        daemon.start();
    }

    public void parseInSimpleThread(String vacancyId) {
        Runnable task = () -> {
            log.info("[SIMPLE THREAD] Запуск парсинга ID: {}", vacancyId);
            parseAndSave(vacancyId);
        };
        new Thread(task).start();
    }

    @Scheduled(fixedRate = 60_000)
    public void scheduledDrainQueue() {
        if (vacancyIdQueue.isEmpty()) return;
        log.info("[SCHEDULED] Забираем из очереди, размер: {}", vacancyIdQueue.size());
        while (!vacancyIdQueue.isEmpty()) {
            String id = vacancyIdQueue.poll();
            if (id != null) {
                executorService.submit(() -> parseAndSave(id));
            }
        }
    }

    private void parseAndSave(String vacancyId) {
        if (processedIds.putIfAbsent(vacancyId, true) != null) return;

        try {
            Map response = hhWebClient.get()
                    .uri("/vacancies/{id}", vacancyId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return;

            Vacancy vacancy = new Vacancy();
            vacancy.setHhId(vacancyId);
            vacancy.setTitle((String) response.get("name"));
            vacancy.setDatePosted((String) response.get("published_at"));

            Map employer = (Map) response.get("employer");
            vacancy.setCompany(employer != null ? (String) employer.get("name") : "");

            Map salary = (Map) response.get("salary");
            vacancy.setSalary(formatSalary(salary));

            Map area = (Map) response.get("area");
            vacancy.setCity(area != null ? (String) area.get("name") : "");

            vacancy.setRequirements((String) response.getOrDefault("description", ""));

            repository.save(vacancy);
            saveToFile(vacancy);

        } catch (Exception e) {
            log.error("[PARSER] Ошибка при обработке ID: {}", vacancyId, e);
        }
    }

    private String formatSalary(Map salary) {
        if (salary == null) return "не указана";
        Object from = salary.get("from");
        Object to = salary.get("to");
        String currency = (String) salary.get("currency");
        if (from == null && to == null) return "не указана";
        if (from != null && to != null) return from + " – " + to + " " + currency;
        return (from != null ? "от " + from : "до " + to) + " " + currency;
    }

    private void saveToFile(Vacancy vacancy) {
        fileLock.lock();
        try (BufferedWriter bw = new BufferedWriter(
                new FileWriter("vacancies.txt", true))) {
            bw.write(vacancy.toString());
            bw.newLine();
        } catch (IOException e) {
            log.error("Ошибка записи в файл", e);
        } finally {
            fileLock.unlock();
        }
    }

    public void addToQueue(String vacancyId) {
        vacancyIdQueue.add(vacancyId);
    }
}