import { render, screen } from '@testing-library/react';
import App from './App';

test('renders vulnerable text', () => {
  render(<App />);
  
  // 🌟 เปลี่ยนมาหาข้อความที่เราเพิ่งแก้ไปแทน
  const textElement = screen.getByText(/Test Integration/i);
  
  expect(textElement).toBeInTheDocument();
});