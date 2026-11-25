# MEM-009 — Decisões

1. **Nome opcional e privado.** Não é obrigatório e não entra no payload público. O proprietário pode consultá-lo no histórico privado de fotos.
2. **Sem rate limit neste incremento.** O produto deixa cota e taxa para I-03. O limite de bytes e o tipo mágico continuam os de MEM-001.
3. **O convidado escolhe o que compartilhar.** Upload validado fica `READY` no álbum privado pessoal e entra no mural universal somente pela ação explícita do mesmo convidado. O anfitrião não aprova fotos.
4. **Dono da foto convidada é o dono do mural.** A chave aleatória do álbum vincula contribuições ao evento; as fotos ainda ficam no bucket privado e não aparecem na biblioteca pessoal pública nem no mural até a seleção do convidado.
5. **QR Code, regeneração dedicada e animação** permanecem nos incrementos I-03 e I-04. Revogar o grant `CONTRIBUTE` já encerra novos envios.
6. **Upload em andamento após revogação.** A conclusão é negada e a contribuição vai para `FAILED`.
7. **Álbum pessoal por navegador.** O QR identifica o evento, e um token aleatório local identifica o álbum privado daquele convidado. O backend persiste apenas o hash do token e valida mural + grant em cada leitura e mutação.
