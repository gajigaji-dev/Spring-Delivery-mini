import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import type { Menu } from '../api/types';

export interface CartLine {
  menu: Menu;
  quantity: number;
}

interface CartContextValue {
  lines: CartLine[];
  storeId: number | null;
  total: number;
  add: (menu: Menu) => void;
  setQuantity: (menuId: number, quantity: number) => void;
  clear: () => void;
}

const CartContext = createContext<CartContextValue | null>(null);

export function CartProvider({ children }: { children: ReactNode }) {
  const [lines, setLines] = useState<CartLine[]>([]);

  const add = useCallback((menu: Menu) => {
    setLines((prev) => {
      // 주문은 한 매장의 메뉴만 가능(다르면 서버 400) → 다른 매장 메뉴를 담으면 장바구니를 새로 시작
      const base = prev.length > 0 && prev[0].menu.storeId !== menu.storeId ? [] : prev;
      const found = base.find((l) => l.menu.id === menu.id);
      // 같은 메뉴는 한 항목으로 합침 (중복 menuId도 서버 400)
      if (found) return base.map((l) => (l.menu.id === menu.id ? { ...l, quantity: l.quantity + 1 } : l));
      return [...base, { menu, quantity: 1 }];
    });
  }, []);

  const setQuantity = useCallback((menuId: number, quantity: number) => {
    setLines((prev) =>
      quantity <= 0 ? prev.filter((l) => l.menu.id !== menuId) : prev.map((l) => (l.menu.id === menuId ? { ...l, quantity } : l)),
    );
  }, []);

  const clear = useCallback(() => setLines([]), []);

  const value = useMemo(() => {
    // 화면 표시용 합계 — 실제 결제 금액은 서버가 주문 당시 메뉴 가격으로 다시 계산
    const total = lines.reduce((sum, l) => sum + l.menu.price * l.quantity, 0);
    return { lines, storeId: lines[0]?.menu.storeId ?? null, total, add, setQuantity, clear };
  }, [lines, add, setQuantity, clear]);

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error('CartProvider 밖에서 useCart를 사용했습니다');
  return ctx;
}
