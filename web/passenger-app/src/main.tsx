import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { AdminDashboard } from './components/AdminDashboard';
import './styles.css';

// Deliberately not a router library. Two surfaces, one path check -- adding
// react-router for this would be more bundle than the feature is worth on a
// congested-3G target. nginx serves index.html for any path, so /admin works
// on refresh and on a direct link.
const isAdmin = window.location.pathname.startsWith('/admin');

createRoot(document.getElementById('root')!).render(
  <StrictMode>{isAdmin ? <AdminDashboard /> : <App />}</StrictMode>,
);
