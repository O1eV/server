-- PostgreSQL 13+. 수동 실행용이며 애플리케이션 시작 시 자동 실행되지 않습니다.
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE public.watchlists IN ACCESS EXCLUSIVE MODE;
DO $$
DECLARE
    id_type text;
BEGIN
    SELECT data_type INTO id_type
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'watchlists' AND column_name = 'id';

    IF id_type = 'uuid' THEN
        RETURN; -- 기존 UUID 값은 그대로 보존합니다.
    END IF;
    IF id_type IS DISTINCT FROM 'bigint' THEN
        RAISE EXCEPTION 'watchlists.id must be bigint or uuid, found %', id_type;
    END IF;
    -- 외부 참조가 있으면 ID를 새로 생성할 수 없으므로 별도 동반 이관이 필요합니다.
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE contype = 'f' AND confrelid = 'public.watchlists'::regclass) THEN
        RAISE EXCEPTION 'watchlists has incoming foreign keys; migrate referencing tables first';
    END IF;

    ALTER TABLE public.watchlists ALTER COLUMN id DROP IDENTITY IF EXISTS;
    ALTER TABLE public.watchlists ALTER COLUMN id DROP DEFAULT;
    ALTER TABLE public.watchlists ALTER COLUMN id TYPE uuid USING gen_random_uuid();
END $$;
COMMIT;
