CREATE TABLE iam_bootstrap (
    id INTEGER PRIMARY KEY,
    initialized BOOLEAN NOT NULL
);
INSERT INTO iam_bootstrap(id,initialized)
SELECT 1, EXISTS(SELECT 1 FROM iam_users WHERE administrator=TRUE);
