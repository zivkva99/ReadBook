package com.example.readbook.data

/** First-launch seeding — without this, [ReadingConfig] never exists until a Settings screen saves one. */
suspend fun ensureConfigSeeded(dao: ReadingConfigDao) {
    if (dao.getConfig() == null) {
        dao.upsert(ReadingConfig(enabledDaysMask = DEFAULT_ENABLED_DAYS_MASK, targetSeconds = DEFAULT_TARGET_SECONDS))
    }
}
