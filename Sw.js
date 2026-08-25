/* =========================================================
   VITTA — service worker
   Coloque este arquivo NA MESMA PASTA do index.html.

   Faz duas coisas:
   1. Notificações: quem realmente mostra o aviso no sistema é o
      service worker. Sem ele, o iPhone não notifica de jeito nenhum
      e o Android só avisa com a aba aberta.
   2. Offline: guarda a última versão do app para abrir sem internet.
      Estratégia "rede primeiro": se houver conexão, sempre pega a
      versão nova do servidor — assim publicar uma atualização não
      esbarra em cópia velha. Sem conexão, serve a cópia guardada.
   ========================================================= */
const CACHE = 'vitta-v1';

self.addEventListener('install', ev => {
  self.skipWaiting();
  ev.waitUntil(caches.open(CACHE).then(c => c.addAll(['./', './index.html']).catch(() => { })));
});

self.addEventListener('activate', ev => {
  ev.waitUntil((async () => {
    const nomes = await caches.keys();
    await Promise.all(nomes.filter(n => n !== CACHE).map(n => caches.delete(n)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', ev => {
  const req = ev.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== self.location.origin) return;
  ev.respondWith((async () => {
    try {
      const resp = await fetch(req);
      const c = await caches.open(CACHE);
      c.put(req, resp.clone()).catch(() => { });
      return resp;
    } catch (e) {
      const cached = await caches.match(req);
      return cached || caches.match('./index.html');
    }
  })());
});

/* O app pede o aviso por mensagem; quem exibe é o worker. */
self.addEventListener('message', ev => {
  const d = ev.data || {};
  if (d.tipo !== 'aviso') return;
  self.registration.showNotification(d.titulo || 'Vitta', {
    body: d.corpo || '',
    tag: d.tag || 'vitta',
    icon: d.icone, badge: d.icone,
    renotify: true,
    requireInteraction: !!d.fixo,
    data: { url: d.url || './' }
  });
});

/* Push de verdade, se um dia você ligar um servidor de push. */
self.addEventListener('push', ev => {
  let d = {};
  try { d = ev.data ? ev.data.json() : {}; } catch (e) { d = { corpo: ev.data && ev.data.text() }; }
  ev.waitUntil(self.registration.showNotification(d.titulo || 'Vitta', {
    body: d.corpo || '', tag: d.tag || 'vitta-push', icon: d.icone, badge: d.icone,
    data: { url: d.url || './' }
  }));
});

/* Tocar no aviso traz o app para a frente em vez de abrir outra aba. */
self.addEventListener('notificationclick', ev => {
  ev.notification.close();
  const alvo = (ev.notification.data && ev.notification.data.url) || './';
  ev.waitUntil((async () => {
    const abas = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const c of abas) {
      if (c.url.includes(self.location.origin)) { await c.focus(); return; }
    }
    await self.clients.openWindow(alvo);
  })());
});