DELETE FROM fo_recovery_proposal
WHERE proposal_id = 'prop-ordercare-m2-http-e2e'
   OR identifier_value = 'ORDERCARE-M05-REQUEST';

DELETE FROM fo_recovery_action_log
WHERE action_request_id LIKE 'act-%'
  AND target_type = 'DEAD_LETTER'
  AND target_key = '9000000000000505';

SELECT 'OrderCare M2 recovery facts cleaned' AS cleanup_status;
