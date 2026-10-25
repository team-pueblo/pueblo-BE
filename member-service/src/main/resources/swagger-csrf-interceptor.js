async function(request) {
  const target = new URL(request.url, window.location.href);
  if (target.origin === window.location.origin && target.pathname.startsWith('/api/v1/')
      && !['GET', 'HEAD', 'OPTIONS'].includes((request.method || 'GET').toUpperCase())) {
    const response = await fetch('/api/v1/auth/csrf', {credentials: 'same-origin', cache: 'no-store'});
    if (!response.ok) throw new Error('Could not initialize CSRF protection');
    const body = await response.json();
    if (!body.data || !body.data.token) throw new Error('Missing CSRF token');
    request.headers = request.headers || {};
    request.headers['X-XSRF-TOKEN'] = body.data.token;
  }
  return request;
}
