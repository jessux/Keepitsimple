package com.keepitsimple

import java.time.LocalDateTime

data class Task(val id: Long, val title: String, val due: LocalDateTime?)
