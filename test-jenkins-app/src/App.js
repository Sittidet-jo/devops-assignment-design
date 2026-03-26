import logo from './logo.svg';
import './App.css';

function App() {
  // 🚨 1. สร้าง BUG: การกำหนดค่าตัวแปรให้ตัวเอง (Self-assignment) 
  // SonarQube จะมองเป็น Major Bug ทันที
  let value = 10;
  value = value; 

  // 🚨 2. สร้าง Code Smell: เงื่อนไขที่เป็นจริงเสมอ
  if (true === true) {
    console.log("This is a code smell");
  }

  return (
    <div className="App">
      <header className="App-header">
        <img src={logo} className="App-logo" alt="logo" />
        <a className="App-link" href="https://example.com">
          Test Integration
        </a>
      </header>
    </div>
  );
}
export default App;