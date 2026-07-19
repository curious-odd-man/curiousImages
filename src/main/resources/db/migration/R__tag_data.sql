MERGE INTO tag t
    USING (
        VALUES
            ('tag1'),
            ('tag2')
        ) AS src(tag)
ON t.tag = src.tag
WHEN NOT MATCHED THEN
    INSERT (tag)
    VALUES (src.tag);