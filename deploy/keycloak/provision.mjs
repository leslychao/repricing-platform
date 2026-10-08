const base = 'http://keycloak:8080/auth';
const required = name => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
};
const admin = required('KC_BOOTSTRAP_ADMIN_USERNAME');
const password = required('KC_BOOTSTRAP_ADMIN_PASSWORD');
const publicUrl = required('PUBLIC_BASE_URL');
const clientSecret = required('OIDC_CLIENT_SECRET');
const users = [
  {id: '5b4db533-c13d-400e-bb00-555c913e9d05', username: 'test', firstName: 'Тестовый', lastName: 'Пользователь', password: required('KEYCLOAK_TEST_PASSWORD')},
  {id: 'c0674d41-d12a-4437-895b-0409e09b60f4', username: 'admin', firstName: 'Администратор', lastName: 'Приложения', password: required('KEYCLOAK_APP_ADMIN_PASSWORD')}
];
let token;
for (let attempt = 0; attempt < 90; attempt++) {
  try {
    const response = await fetch(`${base}/realms/master/protocol/openid-connect/token`, {
      method: 'POST', signal: AbortSignal.timeout(5000),
      body: new URLSearchParams({grant_type: 'password', client_id: 'admin-cli', username: admin, password})
    });
    if (response.ok) { token = (await response.json()).access_token; break; }
  } catch { /* Startup readiness is bounded below; no response or credentials are logged. */ }
  await new Promise(resolve => setTimeout(resolve, 2000));
}
if (!token) throw new Error('Keycloak administration is unavailable');
async function request(path, method = 'GET', body) {
  const response = await fetch(`${base}/admin/realms/repricer${path}`, {
    method, signal: AbortSignal.timeout(10000),
    headers: {Authorization: `Bearer ${token}`, 'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  if (!response.ok) throw new Error(`Keycloak provisioning failed (${response.status})`);
  return response.status === 204 || response.status === 201 ? undefined : response.json();
}
const realm = await request('');
if (realm.attributes?.repricerManaged !== 'true') throw new Error('Refusing to change an unmanaged realm');
const profile = await request('/users/profile');
if (!profile.attributes.some(attribute => attribute.name === 'repricerManaged')) {
  profile.attributes.push({name: 'repricerManaged', displayName: 'Managed by Repricer',
    permissions: {view: ['admin'], edit: ['admin']}, multivalued: false});
  await request('/users/profile', 'PUT', profile);
}
for (const expected of users) {
  const matches = await request(`/users?username=${expected.username}&exact=true&briefRepresentation=false`);
  if (matches.length !== 1 || matches[0].id !== expected.id || matches[0].attributes?.repricerManaged?.[0] !== 'true') {
    throw new Error(`Managed user ${expected.username} is missing or conflicts with an unmanaged identity`);
  }
  await request(`/users/${expected.id}`, 'PUT', {
    ...matches[0], enabled: true, emailVerified: true, requiredActions: [],
    email: `${expected.username}@repricer.local`, firstName: expected.firstName, lastName: expected.lastName
  });
  await request(`/users/${expected.id}/reset-password`, 'PUT', {type: 'password', value: expected.password, temporary: false});
}
const clients = await request('/clients?clientId=repricer');
if (clients.length !== 1) throw new Error('Managed OIDC client is missing');
await request(`/clients/${clients[0].id}`, 'PUT', {
  ...clients[0], secret: clientSecret, redirectUris: [`${publicUrl}/oauth2/callback`],
  webOrigins: [publicUrl], attributes: {...clients[0].attributes, 'post.logout.redirect.uris': `${publicUrl}/login`}
});
console.info('Managed Keycloak users and client are ready');
