import { mkdir, stat } from 'node:fs/promises'

const APP = 'http://localhost:5173'
const OUT = 'D:/dsh/llmall/docs/images'
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

for (let attempt = 0; attempt < 40; attempt += 1) {
  try {
    const response = await fetch('http://127.0.0.1:9222/json/version')
    if (response.ok) break
  } catch {
    // wait
  }
  await sleep(250)
}
await mkdir(OUT, { recursive: true })

const target = await (
  await fetch(`http://127.0.0.1:9222/json/new?${encodeURIComponent(`${APP}/login`)}`, {
    method: 'PUT',
  })
).json()
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  ws.onopen = resolve
  ws.onerror = reject
})
let messageId = 0
const pending = new Map()
const consoleErrors = []
ws.onmessage = (event) => {
  const message = JSON.parse(event.data)
  if (message.id) {
    const entry = pending.get(message.id)
    if (!entry) return
    pending.delete(message.id)
    if (message.error) entry.reject(new Error(message.error.message))
    else entry.resolve(message.result)
    return
  }
  if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
    consoleErrors.push(message.params.args.map((arg) => arg.value ?? arg.description ?? '').join(' '))
  }
  if (message.method === 'Runtime.exceptionThrown') {
    consoleErrors.push(message.params.exceptionDetails?.text || 'exception')
  }
}
function send(method, params = {}) {
  messageId += 1
  const id = messageId
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const result = await send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
    userGesture: true,
  })
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text || 'evaluate failed')
  return result.result.value
}
async function navigate(path, wait = 2500) {
  await send('Page.navigate', { url: `${APP}${path}` })
  await sleep(wait)
}
async function screenshot(name) {
  const result = await send('Page.captureScreenshot', { format: 'png' })
  const path = `${OUT}/${name}.png`
  const { writeFile } = await import('node:fs/promises')
  await writeFile(path, Buffer.from(result.data, 'base64'))
  return path
}

await send('Page.enable')
await send('Runtime.enable')
await send('Emulation.setDeviceMetricsOverride', {
  width: 1440,
  height: 900,
  deviceScaleFactor: 1,
  mobile: false,
  screenWidth: 1440,
  screenHeight: 900,
})
await navigate('/login', 2000)
await evaluate(`localStorage.clear()`)
await navigate('/login', 2800)
console.log('login inputs:', await evaluate(`document.querySelectorAll('input').length`))
await screenshot('frontend-login')

await evaluate(`(() => {
  const inputs = [...document.querySelectorAll('input')];
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
  setter.call(inputs[0], 'Jack');
  inputs[0].dispatchEvent(new Event('input', { bubbles: true }));
  setter.call(inputs[1], '123456');
  inputs[1].dispatchEvent(new Event('input', { bubbles: true }));
  document.querySelector('.submit-button').click();
  return true;
})()`)
await sleep(3000)
console.log('after login:', await evaluate(`location.pathname`))

await navigate('/shops/5', 3400)
const shopState = await evaluate(`(() => ({
  path: location.pathname,
  hasShop: document.body.innerText.includes('海底捞火锅'),
  hasItems: document.body.innerText.includes('商品套餐'),
  firstItem: document.body.innerText.includes('单人套餐'),
  scrollHeight: document.documentElement.scrollHeight
}))()`)
console.log('shop state:', shopState)
await screenshot('frontend-shop')

await navigate('/orders', 2800)
const ordersState = await evaluate(`(() => {
  const id = document.querySelector('.order-card .order-id b')?.textContent || '';
  return {
    path: location.pathname,
    id,
    idLength: id.length,
    has19: /^\\d{19}$/.test(id),
    cardCount: document.querySelectorAll('.order-card').length,
    text: document.body.innerText.slice(0, 120)
  };
})()`)
console.log('orders state:', ordersState)
await screenshot('frontend-orders')

console.log('console errors:', JSON.stringify(consoleErrors))
for (const name of ['frontend-login', 'frontend-shop', 'frontend-orders']) {
  const info = await stat(`${OUT}/${name}.png`)
  console.log(name, info.size, 'bytes')
}
ws.close()
