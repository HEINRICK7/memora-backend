# MEM-010 — Decisões

## MEM-010-ADR-01 — Uma única credencial local

**Decisão:** autenticar somente o e-mail/senha injetados por ambiente no perfil local. Não há valores padrão nem cadastro via API.

**Motivo:** o pedido é um administrador de teste; aceitar identidade estática anônima deixa qualquer dispositivo com acesso à API como proprietário.

**Consequência:** credenciais ficam em `.env` local, fora do Git; BCrypt é usado para validação em memória. Produção continua no perfil OIDC/JWT.

## MEM-010-ADR-02 — Sessão e CSRF

**Decisão:** sessão HTTP com cookie HttpOnly/SameSite Strict, rotação do identificador no login e token CSRF para operações privadas.

**Motivo:** evita armazenar a senha ou token bearer em localStorage/sessionStorage e reduz requisições forjadas por origem externa.

**Consequência:** a configuração de teste por IP em HTTP não fornece confidencialidade do tráfego. Uso além de rede local exige TLS.

## MEM-010-ADR-03 — Proxy same-origin

**Decisão:** o browser chama `/api/v1`; Next encaminha ao hostname privado `backend` no Compose. A porta backend não é publicada na LAN.

**Motivo:** cookies de sessão same-origin e API privada sem expor serviços internos ao Wi-Fi.

**Consequência:** uploads S3 assinados usam o endereço LAN do MinIO e CORS restrito à origem do frontend; console MinIO permanece em loopback.
