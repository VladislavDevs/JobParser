package org.example.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Entity
public class Vacancy {

    // Геттеры и сеттеры для всех полей
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String externalId;   // ID вакансии из API
    private String title;
    private String company;
    private String salary;
    private String requirements;
    private String city;
    private String datePosted;

    public Vacancy() {}

    @Override
    public String toString() {
        return "Vacancy{" +
                "id=" + id +
                ", externalId='" + externalId + '\'' +
                ", title='" + title + '\'' +
                ", company='" + company + '\'' +
                ", salary='" + salary + '\'' +
                ", requirements='" + requirements + '\'' +
                ", city='" + city + '\'' +
                ", datePosted='" + datePosted + '\'' +
                '}';
    }
}