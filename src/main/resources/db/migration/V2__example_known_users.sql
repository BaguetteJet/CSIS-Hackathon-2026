-- The example users.v1 consumer's table (example/KnownUsersConsumer.kt).
-- Keep it if your service needs names or roles; otherwise drop it in your own migration.
CREATE TABLE known_user (
    user_id        VARCHAR(36)     NOT NULL PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    email          VARCHAR(200) NOT NULL,
    role           VARCHAR(30)  NOT NULL,
    restaurant_id  VARCHAR(36)     NULL,
    updated_at     DATETIME(6)  NOT NULL
);
