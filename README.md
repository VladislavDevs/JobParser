# Vacancy Parser (Работа России)

## Запуск
1. Откройте проект в IntelliJ IDEA.
2. Запустите `Main` (кнопка Run).

## Проверка
### 1. Автоматический сбор вакансий
Отправьте POST-запрос:
`http://localhost:8080/api/fetch?page=0&size=50`

Этот запрос обратится к открытому API «Работа России» (`opendata.trudvsem.ru`), получит список ID вакансий и положит их в очередь. Через 60 секунд планировщик (`@Scheduled`) заберёт их и отправит в пул потоков на сохранение.

### 2. Ручное добавление ID
Если вы знаете конкретные ID вакансий, отправьте POST:
`http://localhost:8080/api/parse`

### Пример запроса:

```bash
Invoke-RestMethod -Uri "http://localhost:8080/api/fetch?page=0&size=50" -Method Post
```

### Пример:

Тело (JSON):
```json
{
  "companyCode": "1027700404797",
  "vacancyId": "46a78055-70b5-11f1-ab59-9f54fcecbcef"
} 
```

### Пример запроса:

```bash
Invoke-RestMethod -Uri "http://localhost:8080/api/parse" -Method Post -ContentType "application/json" -Body '{"companyCode":"1027700404797","vacancyId":"46a78055-70b5-11f1-ab59-9f54fcecbcef"}'
```