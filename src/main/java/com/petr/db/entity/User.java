package com.petr.db.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tg_user")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "tg_name")
    private String tgName;

    @Column(name = "has_config", nullable = false)
    private Boolean hasConfig = false;

    @Column(name = "wait_accept", nullable = false, length = 1)
    private String waitAccept = "w";
}