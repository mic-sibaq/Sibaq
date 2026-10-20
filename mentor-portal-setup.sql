-- Run after the existing progression setup, which defines
-- public.mentor_passkey_valid(text, text, text, text).
-- This read-only RPC returns only programs assigned to the verified mentor.
CREATE OR REPLACE FUNCTION public.mentor_list_assigned_programs(
  p_mentor_name text,
  p_passkey text
)
RETURNS TABLE (
  admission_no text,
  student_name text,
  program_code text,
  program_name text,
  mentor_name text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $function$
DECLARE
  profile_row record;
  names_json jsonb;
  codes_json jsonb;
  mentors_json jsonb;
  program_count integer;
  program_index integer;
  assigned_mentor text;
  assigned_code text;
  assigned_name text;
BEGIN
  IF nullif(btrim(p_mentor_name), '') IS NULL
     OR nullif(btrim(p_passkey), '') IS NULL THEN
    RETURN;
  END IF;

  FOR profile_row IN
    SELECT
      sp.admission_no::text AS admission_no,
      sp.name::text AS student_name,
      to_jsonb(sp.programs) AS programs_json,
      to_jsonb(sp.program_codes) AS codes_json,
      to_jsonb(sp.program_mentors) AS mentors_json,
      sp.mentor::text AS legacy_mentor
    FROM public.student_profiles AS sp
    WHERE nullif(btrim(sp.admission_no::text), '') IS NOT NULL
  LOOP
    names_json := CASE
      WHEN jsonb_typeof(profile_row.programs_json) = 'array' THEN profile_row.programs_json
      ELSE '[]'::jsonb
    END;
    codes_json := CASE
      WHEN jsonb_typeof(profile_row.codes_json) = 'array' THEN profile_row.codes_json
      ELSE '[]'::jsonb
    END;
    mentors_json := CASE
      WHEN jsonb_typeof(profile_row.mentors_json) = 'array' THEN profile_row.mentors_json
      ELSE '[]'::jsonb
    END;
    program_count := greatest(
      jsonb_array_length(names_json),
      jsonb_array_length(codes_json),
      jsonb_array_length(mentors_json)
    );

    IF program_count = 0 THEN
      CONTINUE;
    END IF;

    FOR program_index IN 0..(program_count - 1) LOOP
      assigned_name := coalesce(
        nullif(btrim(names_json ->> program_index), ''),
        'Program ' || (program_index + 1)::text
      );
      assigned_code := coalesce(
        nullif(btrim(codes_json ->> program_index), ''),
        'PROGRAM-' || (program_index + 1)::text
      );
      assigned_mentor := nullif(btrim(mentors_json ->> program_index), '');

      IF assigned_mentor IS NULL AND program_count = 1 THEN
        assigned_mentor := nullif(btrim(profile_row.legacy_mentor), '');
      END IF;

      IF assigned_mentor IS NOT NULL
         AND lower(assigned_mentor) = lower(btrim(p_mentor_name))
         AND public.mentor_passkey_valid(
           profile_row.admission_no,
           assigned_code,
           assigned_mentor,
           btrim(p_passkey)
         ) IS TRUE THEN
        admission_no := profile_row.admission_no;
        student_name := profile_row.student_name;
        program_code := assigned_code;
        program_name := assigned_name;
        mentor_name := assigned_mentor;
        RETURN NEXT;
      END IF;
    END LOOP;
  END LOOP;
END;
$function$;

REVOKE ALL ON FUNCTION public.mentor_list_assigned_programs(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mentor_list_assigned_programs(text, text) TO anon, authenticated;
