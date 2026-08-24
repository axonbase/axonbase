const [observerUrl, writerUrl] = process.argv.slice(2);
if (!observerUrl || !writerUrl) throw new Error('uso: live-query.mjs <observer-ws> <writer-ws>');

function connect(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.addEventListener('open', () => resolve(ws), { once: true });
    ws.addEventListener('error', reject, { once: true });
  });
}

function client(ws) {
  let id = 0;
  const pending = new Map();
  const notifications = [];
  ws.addEventListener('message', ({ data }) => {
    const frame = JSON.parse(data);
    if (frame.notification) {
      notifications.push(frame.notification);
      return;
    }
    const resolve = pending.get(frame.id);
    if (resolve) {
      pending.delete(frame.id);
      resolve(frame);
    }
  });
  return {
    async call(method, params) {
      const requestId = ++id;
      ws.send(JSON.stringify({ id: requestId, method, params }));
      const frame = await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error(`timeout em ${method}`)), 10000);
        pending.set(requestId, result => { clearTimeout(timeout); resolve(result); });
      });
      if (frame.error) throw new Error(frame.error.message);
      return frame.result;
    },
    notifications,
  };
}

const observerWs = await connect(observerUrl);
const writerWs = await connect(writerUrl);
const observer = client(observerWs);
const writer = client(writerWs);
try {
  await observer.call('use', ['app', 'main']);
  await writer.call('use', ['app', 'main']);
  await observer.call('query', ['LIVE SELECT * FROM person']);
  await writer.call('query', ['CREATE person:evt1 CONTENT {name:"Live"}']);
  const deadline = Date.now() + 10000;
  while (observer.notifications.length === 0 && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  const event = observer.notifications[0];
  if (!event || event.action !== 'CREATE' || event.result?.name !== 'Live') {
    throw new Error(`live query não recebeu evento confirmado: ${JSON.stringify(event)}`);
  }
  console.log('Live query distribuída validada.');
} finally {
  observerWs.close();
  writerWs.close();
}
