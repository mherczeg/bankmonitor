import { Link, Outlet, createRootRoute } from '@tanstack/react-router'
import styles from './shell.module.css'

export const Route = createRootRoute({ component: AppShell })

const activeLink = { className: 'active' }

function AppShell() {
  return (
    <>
      <nav className="navbar navbar-expand bg-body-tertiary border-bottom">
        <div className="container">
          <span className={`navbar-brand ${styles.brand}`}>Global Payment Service</span>
          <ul className="navbar-nav">
            <li className="nav-item">
              <Link to="/accounts" className="nav-link" activeProps={activeLink}>
                Accounts
              </Link>
            </li>
            <li className="nav-item">
              <Link to="/transfers/new" className="nav-link" activeProps={activeLink}>
                New transfer
              </Link>
            </li>
            <li className="nav-item">
              <Link to="/transfers" className="nav-link" activeOptions={{ exact: true }} activeProps={activeLink}>
                Transactions
              </Link>
            </li>
          </ul>
        </div>
      </nav>
      <main className="container py-4">
        <Outlet />
      </main>
    </>
  )
}
