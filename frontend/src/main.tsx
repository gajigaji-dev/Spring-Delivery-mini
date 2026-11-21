import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import { AuthProvider } from './auth/AuthContext';
import { CartProvider } from './cart/CartContext';
import { ToastProvider } from './components/Toast';
import { OwnerProvider } from './owner/OwnerContext';
import './styles/tokens.css';
import './styles/base.css';
import './styles/owner.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          <CartProvider>
            <OwnerProvider>
              <App />
            </OwnerProvider>
          </CartProvider>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  </StrictMode>,
);

// 웹폰트 CSS는 첫 화면을 그린 뒤에 받음 — 한글 @font-face가 많아 CSS가 크므로 렌더링을 막지 않게 (font-display: swap)
void import('./styles/fonts');
