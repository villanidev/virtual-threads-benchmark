-- wrk script: POST /api/agendamentos with a varying servico_id and time slot
local counter = 0
wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"

request = function()
  counter = counter + 1
  local body = string.format(
    '{"cliente_nome":"Carlos Silva","servico_id":%d,"data_hora":"2026-11-15T%02d:%02d:00"}',
    counter % 50, (counter // 60) % 24, counter % 60)
  return wrk.format(nil, "/api/agendamentos", nil, body)
end
